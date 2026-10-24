# Testing Strategy and Acceptance Scenarios

## Test pyramid

### Unit tests
Test amount parsing (positive only, no exponent notation, scale <= 2, precision <= 19), canonical request hashing, legal status transitions, public error mapping, retry/backoff bounds, and event envelope/schema validation.

### PostgreSQL integration tests
Use Testcontainers PostgreSQL and real Flyway migrations. H2 is not acceptable evidence for PostgreSQL locking/constraint behavior. Verify fresh migration, upgrade migration, request validation, duplicate idempotency key/same request, key reuse/different request, simultaneous idempotency race, one balanced pair per success, no postings for business failure, rollback when failure is injected between ledger inserts, terminal-state idempotency, account balance reconciliation, and stable pagination/filtering.

### Broker integration tests
Use Testcontainers with a real Kafka-compatible broker aligned with Compose. Verify outbox publication/ack marking, durable retry after broker outage, duplicate delivery, consumer durable outcome before ack, malformed/unsupported event to DLQ, recovery after broker restart, duplicate delivery after outcome commit, and retry exhaustion. Mock-based tests may supplement but cannot replace broker integration tests for these claims.

### API tests
Verify validation, HTTP status semantics, response schemas, pagination caps, correlation IDs, error envelope, and that responses omit SQL, stack traces, broker internals, claim tokens, and secrets.

### End-to-end flow
1. Create two synthetic accounts with known opening balances.
2. Submit a transfer with `Idempotency-Key`; response is `202 PENDING`.
3. Wait a bounded interval for terminal state.
4. Assert status, balances, exactly two ledger legs, status history, processing attempts, and outbox/consumer records as appropriate.
5. Retry same key/same request and assert same transfer ID and no new ledger entries.
6. Send an amount above available balance; assert terminal failure and zero ledger rows.
7. Trigger a test-only transient fault; assert durable retry or visible exhaustion/DLQ.
8. Trigger reconciliation and confirm a clean report for consistent fixtures.

Test-only fault injection must be isolated from normal runtime and impossible to enable accidentally in a public deployment.

## Critical concurrency scenario
Given a source account with `100.00`, launch two distinct concurrent transfers for `80.00` each to separate destinations, synchronized to maximize the race. At most one can succeed. The source balance must never be negative and must reconcile with ledger movements. Assert both transfer outcomes, both destination balances, source balance, and ledger counts after the threads finish.

## Failure cases that must be exercised
- Client timeout after API transaction commit, followed by same-key retry.
- Broker unavailable after transfer/outbox commit.
- Publisher crash after broker acknowledgment and before outbox marking.
- Duplicate event delivery.
- Process crash after claim but before processing; recovery after lease expiry.
- DB failure between ledger legs or during balance update; all-or-nothing rollback.
- Transient processing failure and bounded retry.
- Retry exhaustion with visible terminal state and DLQ/operations issue.
- Unsupported event version.
- Reconciliation finds deliberate test-fixture mismatch.

## CI evidence
The required main-branch path must build from a clean checkout and run unit plus integration tests (for example `./mvnw -B verify`) on a Docker-capable runner. Do not silently skip Testcontainers tests on CI. If local Docker is unavailable, record integration tests as blocked locally and depend on CI evidence; do not report them as passed. Never weaken assertions just to get green output. Record exact commands and observed counts in `docs/PHASE_LOG.md`.
