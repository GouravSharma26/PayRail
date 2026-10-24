# Eventing, Outbox, Retry, Dead Letter, and Recovery

## 1. Delivery contract

The database transaction and broker publish cannot be made one atomic operation using ordinary PostgreSQL + Kafka APIs alone. Use the transactional outbox and **at-least-once delivery** with idempotent consumers. Duplicate publication and duplicate delivery are expected crash cases, not exceptional impossibilities.

## 2. Event envelope

Use a versioned JSON envelope, for example:

```json
{
  "eventId":"00000000-0000-0000-0000-000000000101",
  "eventType":"transfer.requested",
  "schemaVersion":1,
  "occurredAt":"2026-01-01T00:00:00Z",
  "aggregateType":"transfer",
  "aggregateId":"00000000-0000-0000-0000-000000000099",
  "aggregateSequence":1,
  "correlationId":"00000000-0000-0000-0000-000000000001",
  "payload":{"transferId":"00000000-0000-0000-0000-000000000099"}
}
```

Keep the payload small; use the transfer ID to fetch authoritative current state. Never put secrets, real identity data, or unnecessary balances in broker messages. Use the transfer ID as broker message key to keep related events on the same partition where possible.

## 3. Topics

Choose a documented topic naming scheme and create/verify topics at startup or through local Compose setup. Recommended logical topics:

- `payrail.transfer-commands.v1`: initial transfer requests and durable retries (event type identifies command).
- `payrail.transfer-commands.dlq.v1`: exhausted/unparseable/unsupported messages with source topic, original event ID, failure code, timestamp, and safe payload or a restricted payload reference.

A separate event topic for reporting is optional and out of MVP. Topic partition count and retention must be configured explicitly. Do not use a single unbounded retry loop.

## 4. Transactional outbox publisher

1. Query due unpublished rows, using a short claim transaction and `FOR UPDATE SKIP LOCKED` or a robust lease protocol (`AND (last_error_code IS NULL OR last_error_code <> 'PUBLISH_RETRY_EXHAUSTED')`).
2. Assign a lease token and expiry, increment publish attempt count, commit.
3. Publish with broker acknowledgement, a stable event ID, and transfer ID key. For DLQ events (`transfer.dlq`), events are recorded with an independent aggregate identity (`aggregate_type = 'dlq'`, `aggregate_id = eventId`, `aggregate_sequence = 1L`) to prevent concurrent sequence collisions under `MAX(seq) + 1`, and published to `payrail.transfer-commands.dlq.v1` using `correlation_id` as the message key.
4. After broker acknowledgement, mark `published_at` only if lease token still matches.
5. On publish failure, save a safe error code/message (`BROKER_PUBLISH_FAILED`), clear/expire lease, and schedule next retry with bounded exponential backoff + jitter.
6. Enforce bounded publish retries: when `publish_attempts >= maxRetries` (default 5), persist inspectable terminal failure `PUBLISH_RETRY_EXHAUSTED`. Exhausted events are excluded from automatic publisher sweeps, but explicitly prevent the pending recovery sweep from generating duplicate command events, preserving operator visibility and auditability. Operator recovery is supported via `outboxRepository.resetForReplay(eventId, now)`.
7. If the process crashes after broker ack and before database mark, it halts ungracefully: the lease remains intact in PostgreSQL until expiry, and upon lease recovery the event is republished. Consumer dedupe and transfer-state checks absorb this duplicate delivery.

Do not hold a database transaction or row lock open while waiting for the broker. Publishers running on multiple app instances must not claim the same row concurrently except through lease expiry/crash behavior.

## 5. Consumer and posting flow

- Validate event envelope fields, schema version, and event ID.
- Malformed JSON, invalid envelopes, or unsupported schema versions durably record a DLQ outbox event in PostgreSQL within a database transaction and mark the message processed. If DB is unavailable, exception propagates to container error handler.
- Ignore event if a committed `processed_messages` record exists for this consumer and event ID.
- Claim transfer with a short transaction, random claim token, and lease expiry.
- If transfer is terminal, record the message as handled and do not post again.
- In the posting transaction, lock transfer and accounts; check claim token; validate; update balances; insert ledger legs; update status/history/attempt outcome; add processed message. Commit all writes atomically.
- On permanent business decline, lock transfer, verify claim token, and commit terminal `FAILED` status with safe error code and 0 ledger entries.
- On retryable technical failure, pass `claimToken` into failure recovery transaction: lock transfer row `FOR UPDATE`, verify `status == PROCESSING` and claim token still matches. If stale or claim replaced, exit without mutating state. If valid, increment attempt outcome, reset to `PENDING` (or mark `FAILED` with `RETRY_EXHAUSTED` and enqueue DLQ outbox event if attempts exhausted), and schedule next retry via non-colliding outbox aggregate sequence (`MAX(seq) + 1`).
- Unhandled consumer exceptions (such as database failure during DLQ recording, before a durable claim, or within the retry-state transaction) are re-thrown and propagated to Spring Kafka's `CommonErrorHandler`. The configured `DefaultErrorHandler` utilizes `DurableRecoveryEnforcingRecordRecoverer` with a bounded 250ms backoff, refusing to commit or skip offsets on retry exhaustion (`commitRecovered = false`, `resetStateOnRecoveryFailure = true`). Records are preserved until PostgreSQL recovers and processing/DLQ recording is durable.

## 6. Idempotency layers (not interchangeable)

1. API idempotency key + request hash: prevents duplicate logical transfers from client retries.
2. Outbox event ID: identifies a specific durable event publication.
3. Consumer dedupe key `(consumer_name, event_id)`: absorbs duplicate delivery of the same event.
4. Transfer terminal-state guard and unique ledger legs: prevent a logically repeated transfer processing attempt from posting twice even if different event IDs point to the same transfer.

All four are required. Kafka producer idempotence helps at the broker boundary but does not replace application-level idempotency or database uniqueness.

## 7. Backoff and retry limits

Keep configuration externalized, with conservative defaults, such as `max-attempts=5`, `initial-backoff=1s`, `max-backoff=60s`, and jitter. Use UTC `available_at` timestamps in the outbox rather than sleeping a consumer thread for long delays.

- Retry only transient technical failures.
- Do not retry permanent business declines such as insufficient funds.
- Durable DLQ delivery: Dead-letter work (malformed payload, invalid schema, missing transfer, or retry exhaustion) is recorded in `outbox_events` with independent aggregate identity (`aggregate_type = 'dlq'`, `aggregate_id = eventId`, `aggregate_sequence = 1L`) within the active DB transaction and published asynchronously by the leased outbox publisher with broker ack and retry tracking. Swallowed broker errors during DLQ delivery are eliminated.
- On exhaustion, transfer status is marked terminal `FAILED` (`RETRY_EXHAUSTED`), attempt outcome recorded, DLQ outbox event enqueued, and reconciliation issue flagged.
- Controlled operator replay workflow: operator can inspect exhausted outbox events, fix underlying infrastructure issues, and invoke `resetForReplay(eventId, now)` to safely re-queue the event.

## 8. Recovery sweep

A scheduled recovery sweep detects and safely acts on:
- Processing lease expired beyond a grace period: In the same transaction as the transfer reset, finalizes the affected stale attempt record fenced by `(transfer_id = :transferId AND attempt_number = :attemptNumber AND claim_token = :claimToken AND outcome = 'IN_PROGRESS')`. If below attempt limit, sets attempt outcome to `RETRY_SCHEDULED`, resets transfer to `PENDING`, and enqueues recovery command event. If at attempt limit, sets attempt outcome to `EXHAUSTED` (`LEASE_EXPIRED`), transitions transfer to `FAILED`, and enqueues DLQ record. Each stale processing candidate runs in an isolated `TransactionTemplate` transaction; a failed fenced finalization rolls back that candidate's complete transaction, logs the error, and allows the sweep to continue processing other stale candidates and subsequent recovery categories (`sweepStaleOutboxLeases()` and `sweepStalePendingTransfers()`).
- Unpublished outbox event with expired lease: releases stale publisher lease.
- Stale `PENDING` transfers: locks candidate transfer `FOR UPDATE`, verifies `status == PENDING` and that no unpublished or exhausted outbox events exist (`hasUnpublishedEvents == false`). A transfer with a `PUBLISH_RETRY_EXHAUSTED` event does not acquire endless replacement command events; recovery requires explicit operator replay. Unrelated candidates recover independently even if one encounters a conflict.
- Technical retry exhaustion and outbox publish exhaustion (`PUBLISH_RETRY_EXHAUSTED`) are flagged in continuous reconciliation.

## 9. Reconciliation checks

At minimum, each run checks:

1. Every `SUCCEEDED` transfer has exactly two ledger entries.
2. Its debit and credit have equal amount/currency and correct source/destination accounts.
3. Every non-successful transfer has zero transfer ledger entries.
4. Every account `current_balance` equals opening baseline plus incoming credits minus outgoing debits.
5. No ledger entry has a currency different from its transfer/account.
6. No stale `PROCESSING` lease exceeds threshold without a retry/recovery plan.
7. No old unpublished outbox event exceeds configured threshold.
8. No outbox events stuck in `PUBLISH_RETRY_EXHAUSTED` state.
9. No duplicate logical ledger posting exists (backed by unique constraints).

Record counts, run start/end, issue type/severity, associated IDs, and safe observed values. Never delete/rewrite ledger rows or silently alter balance/status.

## 10. Failure injection matrix

Document and test these cases:

| Failure point | Expected durable outcome |
|---|---|
| Broker down after API transaction | Transfer stays `PENDING`; outbox stays unpublished and retries when broker recovers |
| Broker down during DLQ publication | DLQ event stored durably in outbox; published when broker recovers; no repeated financial effects |
| Outbox publisher permanently fails broker sends | Exhausts retries at max limit (5), marks `PUBLISH_RETRY_EXHAUSTED`, excludes from auto-claims, flags in reconciliation, replayable via operator API |
| Publisher crash after broker ack before DB update | Event remains unpublished with lease intact until expiry; republished; consumer deduplication absorbs duplicate delivery |
| Stale PENDING transfer recovery | Allocates sequence `MAX(seq) + 1` (no collision with published seq 1); concurrent sweepers and worker claim races handled safely |
| Stale worker resumes failure handling after SUCCEEDED or FAILED | Claim fencing checks `claimToken` and `PROCESSING` status; exits without overwriting state, ledger entries, or enqueuing duplicate retries |
| Database failure before durable claim or in retry-state tx | Exception propagated to Spring Kafka `CommonErrorHandler` with bounded backoff; source event not silently acknowledged |
| Database failure during malformed message DLQ recording | Listener container retries with bounded backoff; refuses offset commit on exhaustion; message redelivered and recovered when DB restored |
| Concurrent DLQ events for same transfer | Independent aggregate identity (`aggregate_type = 'dlq'`, `aggregate_id = eventId`) guarantees zero sequence collisions |
| Stale processing lease below retry cap | Finalizes stale attempt as `RETRY_SCHEDULED`, resets transfer to `PENDING`, enqueues recovery command |
| Stale processing lease at retry cap | Finalizes stale attempt as `EXHAUSTED` (`LEASE_EXPIRED`), transitions transfer to `FAILED`, enqueues DLQ event |
| Stale candidate with mismatched claim token | Transaction rolls back completely; candidate unchanged (IN_PROGRESS); sweep continues to other candidates and sweep categories |
| Stale PENDING transfer with exhausted outbox event | Ignored by automatic recovery sweep; no duplicate commands generated; resumes upon operator replay |
| Client timeout after API commit | Same idempotency key returns the same transfer |
| Duplicate message delivered twice | One logical transfer result and one ledger pair at most |
| App crashes after claim before processing | Expired lease recovery schedules work with non-colliding aggregate sequence |
| DB failure during second ledger insert | Entire posting transaction rolls back; no partial balance or ledger mutation |
| Two transfers race for the same source balance | Locking serializes them; balance never becomes negative |
| Transient processing failure | Durable retry event scheduled with backoff, attempt visible |
| Retry cap exhausted | Terminal `FAILED` (`RETRY_EXHAUSTED`); DLQ record enqueued in outbox; 0 ledger entries |
| Malformed/unsupported schema | Safe DLQ outbox path; error propagated if DB unavailable; no silent discard |
