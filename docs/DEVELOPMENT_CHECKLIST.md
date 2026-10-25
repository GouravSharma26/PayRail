# Development Checklist

Use checkboxes only after verified implementation. Initially all implementation tasks remain unchecked.

## Phase 0 — Setup / decisions

- [x] Existing repository and `git status` inspected; no prior work overwritten.
- [x] Java 21 and Docker/Compose verified.
- [x] Compatible framework/dependency/image versions verified from official docs and pinned.
- [x] `docs/MODEL_AND_TECH_DECISIONS.md` updated with exact versions, dates, sources, and deviations.
- [x] Synthetic-only boundary documented in README and API/UI design.
- [x] Maven Wrapper and `.gitignore` reviewed; secrets are excluded.

## Phase 1 — Core domain and REST API

- [x] Spring Boot app starts with explicit configuration and structured errors.
- [x] Flyway migration succeeds on fresh PostgreSQL.
- [x] Accounts can be created/listed/read with synthetic data only.
- [x] Transfer request validation is strict and amounts use decimal strings / `BigDecimal`.
- [x] API idempotency record, transfer, initial history, and outbox row are atomic.
- [x] Same idempotency key/same request returns original transfer.
- [x] Same key/different request returns documented conflict.
- [x] Transfer status and search APIs are paginated and filtered.
- [x] Tests and clean build pass; no transfer falsely appears succeeded.

## Phase 2 — Ledger / consistency

- [x] Account row locks are obtained in deterministic order.
- [x] Double-entry pair + balances + successful status commit atomically.
- [x] Permanent business declines produce no ledger postings.
- [x] Unique constraints prevent duplicate ledger legs.
- [x] Ledger mutation APIs/repository methods are absent.
- [x] Insufficient-funds and concurrent spending tests pass on PostgreSQL.
- [x] Failure between posting steps leaves no partial effects.
- [x] Reconciliation checks exist and do not auto-repair.

## Phase 3 — Asynchronous processing

- [x] Outbox publisher has durable lease, bounded batch, and enforced retry limit (`maxRetries=5`).
- [x] Publish occurs outside DB transaction; published marker follows broker ack.
- [x] Event envelope is versioned and uses transfer ID key.
- [x] Consumer dedupe and terminal state guard are implemented.
- [x] Retry scheduling and original-event dedupe are atomic.
- [x] DLQ delivery is durable: recorded in outbox with independent aggregate identity (`aggregate_type = 'dlq'`, `aggregate_id = eventId`) to eliminate concurrent sequence collisions, and published asynchronously without swallowed errors.
- [x] Outbox retries are bounded: exhausted events marked `PUBLISH_RETRY_EXHAUSTED`, excluded from auto-claims, visible to reconciliation and operator replay.
- [x] Stale pending recovery is transactional, idempotent, allocates non-colliding sequences (`MAX(seq) + 1`), and ignores transfers with exhausted outbox events (no duplicate command storms).
- [x] Worker failure handling (`handleTechnicalFailure`/`handleBusinessDecline`) is claim-fenced (`claimToken` and `status == PROCESSING` verified under row lock).
- [x] Expired worker leases finalize stale attempt records (`RETRY_SCHEDULED` or `EXHAUSTED`) fenced by claim token and transfer ID; candidate execution is isolated so failures roll back individually without blocking other candidates or sweep categories.
- [x] Explicit container-level error policy (`DurableRecoveryEnforcingRecordRecoverer`): messages preserved uncommitted on DB outage/exhaustion and redelivered cleanly upon recovery.
- [x] Expired lease/outbox recovery is idempotent and multi-instance safe.
- [x] Broker and DB outage/duplicate/crash/deadlock/concurrency tests pass (127 tests passing).
- [x] Delivery semantics are accurately described as at-least-once + idempotent effects.

## Phase 4 — Operations and CI

- [x] Reconciliation runs/issues are persisted and searchable (`ReconciliationRunRepository`, `ReconciliationIssueRepository`, `GET /api/v1/ops/reconciliation/runs`, `GET /api/v1/ops/reconciliation/runs/{runId}`).
- [x] Transfer history, attempt details, outbox diagnostics and safe ops APIs exist (`GET /api/v1/transfers/{id}/history`, `GET /api/v1/transfers/{id}/attempts`, `GET /api/v1/ops/outbox/status`, `GET /api/v1/ops/health/summary`).
- [x] Readiness/liveness endpoints expose only intended details (`/actuator/health/liveness`, `/actuator/health/readiness` with `readinessState,db,kafka`).
- [x] JSON logs include correlation IDs and exclude secrets/raw idempotency keys (`JsonLoggingLayout` in `logback-spring.xml`).
- [x] Metrics have bounded labels and include backlog/retry/failure signals (`PayRailMetrics`, `BacklogGauges`, `/actuator/prometheus`).
- [x] Docker Compose health checks and `backend` profile work cleanly (`docker compose --profile app up`).
- [x] CI runs required unit and Testcontainers integration tests (`.github/workflows/ci.yml`).
- [x] End-to-end walkthrough passes from a clean checkout (166 tests passing).
- [x] Documentation matches actual code and no unverified claims remain (`docs/API_CONTRACT.md`, `docs/DEPLOYMENT_AND_CI.md`).

## Phase 5 — Dashboard

- [ ] Phase 1–4 gates passed before frontend work started.
- [ ] Synthetic-only banner always visible.
- [ ] Account and transfer tables are paginated and handle API errors.
- [ ] Transfer detail displays status history, attempts, and safe failure reasons.
- [ ] Reconciliation/ops views are read-only or properly protected.
- [ ] Frontend does not calculate authoritative financial balances.
- [ ] Lint/typecheck/tests/build pass.

## Phase 6 — Final review

- [ ] Clean-checkout instructions have been followed.
- [ ] Idempotency duplicate replay walkthrough is verified.
- [ ] Concurrency overspend walkthrough is verified.
- [ ] Transient broker/failure recovery walkthrough is verified.
- [ ] Reconciliation clean/issue scenarios are verified.
- [ ] Git diff/status reviewed for secrets, local data, generated files, and unrelated changes.
- [ ] README contains only tested commands/features/URLs/results.
- [ ] `docs/PHASE_LOG.md` contains actual commands, test counts, commit SHAs, limitations.
