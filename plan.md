# PayRail Implementation Plan

## Delivery rule

Implement phase-by-phase, with a tested, buildable repository at every phase boundary. Do not treat the roadmap as proof of completion. Completion is based on commands run, tests passing, reviewed code, and recorded evidence.

## Phase 0 — Workspace reconnaissance and design lock

### Tasks
- Inspect repository, installed Java/JDK, Maven, Docker, and Git; do not overwrite existing work.
- Verify the current supported Java 21-compatible Spring Boot line and compatibility of Spring Kafka, Flyway, Testcontainers, observability, and OpenAPI dependencies from official docs.
- Select Maven, JDBC-based persistence for the critical path, PostgreSQL, Kafka-compatible broker, and the repository layout. Record exact versions in `pom.xml`, compose files, and `docs/MODEL_AND_TECH_DECISIONS.md`.
- Create `.env.example`, `.gitignore`, basic README/documentation structure, and initial CI plan. Do not create empty source stubs for every future feature.

### Gate
- Proposed repository layout and version choices are recorded.
- Synthetic-only scope and non-goals are clear.
- No application feature is claimed implemented.

## Phase 1 — Core domain, API, migrations, and local runtime

### Tasks
- Scaffold Spring Boot backend with configuration profiles, health endpoint, request validation, exception mapping, structured logging, and API documentation.
- Add Flyway migration(s) for synthetic accounts, transfers, transfer status history, idempotency records, ledger entries, and audit fields as specified in `docs/DATA_MODEL.md`.
- Add account creation/list/detail/balance APIs and transfer submission/read/search endpoints.
- Implement transfer request canonicalization, amount/currency validation, legal state transitions, and durable idempotency.
- Provide deterministic synthetic seed data only for development/test; do not seed production with fixed demo credentials.
- Add docker compose for PostgreSQL and selected broker with health checks. Backend may initially use a no-op event publication adapter only if clearly isolated and not enabled as if production-ready; prefer completing a minimal outbox early.

### Gate
- App builds from a clean checkout with Maven Wrapper.
- Flyway applies to an empty PostgreSQL database.
- API validation and idempotency tests pass.
- No transfer can be marked `SUCCEEDED` until the ledger-posting phase implements the transaction path.

## Phase 2 — Ledger correctness and concurrency

### Tasks
- Implement a transaction processing service that locks transfer and account rows, locks accounts in deterministic UUID order, validates currency/status/balance, writes exactly two ledger entries, updates both account balances, writes status history, and marks the transfer terminal in one database transaction.
- Add unique constraints to prevent duplicate debit/credit legs, explicit balance checks, and terminal-state guards.
- Build reconciliation queries for posting count, per-transfer debit/credit totals, currency mismatches, and account balance versus opening balance plus ledger net movement.
- Use failure injection in tests to prove all-or-nothing behavior.

### Gate
- Parallel spending test proves only transfers within available balance succeed.
- Successful transfers have exactly one balanced debit/credit pair.
- Forced failure between posting operations leaves no partial ledger or balance update.
- Repeated processing cannot duplicate postings.

## Phase 3 — Outbox and asynchronous broker processing

### Tasks
- Implement transactional outbox insert during transfer submission.
- Implement leased outbox publisher with row claiming, publication confirmation, attempt counters, bounded backoff, and safe lease expiry.
- Implement a broker consumer with schema-versioned event envelope, event ID deduplication, retry scheduling, dead-letter topic, consumer lag/error metrics, and a recovery sweep for stale processing leases/outbox records.
- Implement bounded retries for transient failures and permanent failure codes for business declines.
- Add a reconciliation job that records reports/issues, flags stale transfers or outbox backlog, and never silently rewrites ledger/account state.

### Gate
- API transfer persists even when broker is unavailable; outbox later publishes when broker returns.
- Duplicate event delivery cannot duplicate the transfer, balance effects, status transition, or ledger entries.
- Publisher crash after Kafka acknowledgment is safe (duplicate message absorbed).
- Transient failure is retried; exhausted technical failures are visible in a DLQ/report and do not appear successful.
- Stale processing/outbox work is recoverable by a scheduled process.

## Phase 4 — Test hardening, observability, and operational APIs

### Tasks
- Add comprehensive PostgreSQL/broker Testcontainers integration tests and concurrency/failure tests.
- Add `/actuator/health`, readiness/liveness groups, Prometheus metrics endpoint if enabled, trace/correlation ID, JSON logs, and sensible log redaction.
- Add transaction search with pagination and filters, transfer status history, processing attempt details, failed-payment diagnostics, outbox status, and reconciliation report APIs.
- Add Docker Compose profiles, a safe `.env.example`, deployment documentation, and GitHub Actions CI.
- Add OpenAPI docs and useful curl examples.

### Gate
- Clean CI passes on a fresh checkout.
- Integration tests use disposable services, not a developer's local data.
- Operational failures can be diagnosed without inspecting secrets.
- No undocumented manual DB edits are required to run the demo.

## Phase 5 — Dashboard (after backend gates pass)

### Tasks
- Add React + TypeScript dashboard only after Phases 1–4 pass.
- Show simulator banner, transfer search/status, timeline, synthetic accounts/balances, processing attempt details, reconciliation reports, outbox/DLQ diagnostics, and service health.
- Provide accessible loading/empty/error states and pagination. Never show secrets or expose arbitrary admin SQL.
- Keep all money amounts formatted from decimal strings. No frontend calculation may be authoritative.

### Gate
- Dashboard works against documented API, states synthetic-only scope, and has tests for main workflows and API errors.

## Phase 6 — Demo packaging and final audit

### Tasks
- Run the full documented setup from a clean checkout.
- Verify Compose health, migration application, API walkthrough, duplicate retry walkthrough, concurrent spending scenario, transient broker recovery, and reconciliation output.
- Check all docs against code and remove unsupported claims.
- Add architecture diagram, state machine, event flow, known limitations, screenshots if genuinely available, and exact verified test results to README.

### Gate
- A reviewer can build, run, test, and reproduce the main scenarios with the README alone.
- CI and local checks pass; remaining limitations are explicit.
