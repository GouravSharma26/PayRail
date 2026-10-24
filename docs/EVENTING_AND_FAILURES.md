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

1. Query due unpublished rows, using a short claim transaction and `FOR UPDATE SKIP LOCKED` or a robust lease protocol.
2. Assign a lease token and expiry, increment publish attempt count, commit.
3. Publish with broker acknowledgement, a stable event ID, and transfer ID key.
4. After broker acknowledgement, mark `published_at` only if lease token still matches.
5. On publish failure, save a safe error code/message, clear/expire lease, and schedule next retry with bounded exponential backoff + jitter. Keep row durable.
6. If the process crashes after broker ack and before database mark, it may publish again. Consumer dedupe and transfer-state checks must absorb this.

Do not hold a database transaction or row lock open while waiting for the broker. Publishers running on multiple app instances must not claim the same row concurrently except through lease expiry/crash behavior.

## 5. Consumer and posting flow

- Validate event envelope fields, schema version, and event ID.
- Ignore event if a committed `processed_messages` record exists for this consumer and event ID.
- Claim transfer with a short transaction, random claim token, and lease expiry. Do not expose tokens to API clients.
- If transfer is terminal, record the message as handled and do not post again.
- If another unexpired claim is active, do not race the processor. Either leave the message unacknowledged for a controlled retry or acknowledge only when the stale-lease recovery job guarantees a durable replacement event. Make one behavior explicit and test it.
- In the posting transaction, lock transfer and accounts; check claim token; validate; update balances; insert ledger legs; update status/history/attempt outcome; add processed message. Commit all writes together.
- On permanent business failure, commit a terminal failed transfer and safe reason, with zero ledger entries.
- On retryable infrastructure failure, roll back the posting transaction. In a separate bounded transaction, record the attempt outcome, return transfer to `PENDING` or set `FAILED` at max attempts, insert a due-later retry outbox event if retrying, and mark the original event processed. These actions must commit together so a crash cannot lose the retry.

Avoid relying only on framework retry annotations if they cannot coordinate with the durable transfer attempt/outbox state. Framework retry can be used for short transient database errors within a single call, but the application-level retry schedule remains durable and bounded.

## 6. Idempotency layers (not interchangeable)

1. API idempotency key + request hash: prevents duplicate logical transfers from client retries.
2. Outbox event ID: identifies a specific durable event publication.
3. Consumer dedupe key `(consumer_name, event_id)`: absorbs duplicate delivery of the same event.
4. Transfer terminal-state guard and unique ledger legs: prevent a logically repeated transfer processing attempt from posting twice even if different event IDs point to the same transfer.

All four are required. Kafka producer idempotence helps at the broker boundary but does not replace application-level idempotency or database uniqueness.

## 7. Backoff and retry limits

Keep configuration externalized, with conservative defaults, such as `max-processing-attempts=5`, `initial-backoff=1s`, `max-backoff=60s`, and jitter. Values are examples; make them configurable and test their behavior. Use UTC `available_at` timestamps in the outbox rather than sleeping a consumer thread for long delays.

- Retry only transient technical failures.
- Do not retry permanent business declines such as insufficient funds.
- On exhaustion, transfer status and attempt outcome must be visible; publish or forward a safe summary to the DLQ; record a reconciliation/operations issue.
- If event schema is unsupported or malformed, do not repeatedly poison the main consumer; send to DLQ with a stable failure code and an alert/metric.
- Provide an operator workflow for inspect → fix root cause → explicitly replay a selected event. Never auto-replay all DLQ records without inspection.

## 8. Recovery sweep

A scheduled recovery sweep should detect and safely act on:
- Processing lease expired beyond a grace period.
- Unpublished outbox event with no active lease.
- Outbox event over an age threshold.
- Transfer `PENDING` for too long without an outbox event or with no viable published/retry work.
- Technical retry exhaustion/DLQ records that have no current reconciliation issue.

The sweep must use DB locks/unique constraints and idempotent operations so multiple instances can run. It must not mark transfer `SUCCEEDED` without ledger writes.

## 9. Reconciliation checks

At minimum, each run should check:

1. Every `SUCCEEDED` transfer has exactly two ledger entries.
2. Its debit and credit have equal amount/currency and correct source/destination accounts.
3. Every non-successful transfer has zero transfer ledger entries.
4. Every account `current_balance` equals opening baseline plus incoming credits minus outgoing debits.
5. No ledger entry has a currency different from its transfer/account.
6. No stale `PROCESSING` lease exceeds threshold without a retry/recovery plan.
7. No old unpublished outbox event exceeds the configured threshold.
8. No duplicate logical ledger posting exists (also backed by unique constraints).

Record counts, run start/end, issue type/severity, associated IDs, and safe observed values. The report is diagnostic. Never delete/rewrite ledger rows or silently set balance/status to match expected values.

## 10. Failure injection matrix

Document and test these cases:

| Failure point | Expected durable outcome |
|---|---|
| Broker down after API transaction | Transfer stays `PENDING`; outbox stays unpublished and retries when broker recovers |
| Client timeout after API commit | Same idempotency key returns the same transfer |
| Publisher crashes after broker ack before marking published | Duplicate event may appear; consumer safely deduplicates |
| Duplicate message delivered twice | One logical transfer result and one ledger pair at most |
| App crashes after claim before processing | Expired lease recovery schedules work |
| DB failure during second ledger insert | Entire posting transaction rolls back; no partial balance or ledger mutation |
| Two transfers race for the same source balance | Locking serializes them; balance never becomes negative |
| Transient processing failure | Durable retry event scheduled, attempt visible |
| Retry cap exhausted | Not successful; status/failure issue/DLQ are visible |
| Malformed/unsupported schema | Safe DLQ path and metric; no silent discard |
