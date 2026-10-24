# Antigravity Workflow and Prompt Pack

This document is intended to be copied into Antigravity conversations as the work advances. Keep each task scoped to one phase or one acceptance correction. The repository `AGENTS.md` and `.agents/rules/` provide persistent rules; prompts here are task-specific workflows.

## Before the first code change — Phase 0 prompt

```text
Read AGENTS.md, plan.md, and every documentation file that affects the architecture. Inspect the current workspace and git status; do not overwrite existing code. Execute Phase 0 only. Verify JDK 21, Docker/Compose, and official compatibility of the selected Spring Boot, Spring Kafka, Flyway, PostgreSQL driver, OpenAPI library, and Testcontainers versions. Propose a repository tree and exact pinned version choices. Confirm that the application is synthetic-only and has no real bank/provider integration. Update docs/MODEL_AND_TECH_DECISIONS.md with actual choices and sources. Do not implement future phases. Report decisions, risks, commands run, and anything blocked. Stop at the Phase 0 gate in plan.md.
```

## Phase 1 prompt — Core app and API

```text
Implement Phase 1 from plan.md only. Re-read docs/ARCHITECTURE.md, DOMAIN_MODEL.md, DATA_MODEL.md, API_CONTRACT.md, SECURITY_AND_SAFETY.md, and .agents/rules/database.md. Build a small, clean Spring Boot modular monolith with Flyway migrations, explicit DTOs, request validation, structured error envelopes, correlation IDs, account APIs, transfer submission/read/search, and database-backed idempotency. The initial transfer must be PENDING and durably coupled to an outbox event; do not mark it SUCCEEDED until the posting implementation exists. Use PostgreSQL through Docker/Testcontainers, not H2 for locking claims. Write tests for all endpoints, canonical request hashing, duplicate/reused idempotency keys, and migration startup. Run the build and relevant tests, update docs/DEVELOPMENT_CHECKLIST.md and PHASE_LOG.md with exact evidence, review the diff, and stop at the Phase 1 gate.
```

## Phase 2 prompt — Ledger and race safety

```text
Implement Phase 2 only. Read DOMAIN_MODEL.md and DATA_MODEL.md carefully. Implement a single database transaction that validates and locks the transfer/accounts, locks account IDs in deterministic order, updates both cached balances, writes one matching DEBIT and one CREDIT ledger row, writes status history/attempt outcome, and marks SUCCEEDED atomically. Business declines must commit FAILED without postings. Keep ledger rows immutable and add unique constraints. Implement reconciliation queries but never auto-repair state. Add PostgreSQL Testcontainers tests for successful posting, insufficient funds, invalid/inactive/currency-mismatched accounts, injected mid-posting failure, duplicate processor calls, and concurrent transfers whose combined spend exceeds available balance. Run tests and verify balance/ledger/status/history together. Update logs/checklist and stop at the Phase 2 gate.
```

## Phase 3 prompt — Outbox, broker, retry, and DLQ

```text
Implement Phase 3 only. Read ARCHITECTURE.md and EVENTING_AND_FAILURES.md end to end before editing. Implement a durable leased outbox publisher; publish outside DB transactions; mark published only after broker ack and matching lease token. Implement a versioned event envelope, Kafka-compatible consumer, processed_messages dedupe, bounded durable retry scheduling, dead-letter handling, and expired-lease recovery. Do not claim exactly-once; document at-least-once delivery and prove duplicate safety. Do not acknowledge work before its durable outcome/retry decision commits. Add real broker + PostgreSQL Testcontainers tests for broker outage/recovery, duplicate delivery, publisher crash window, app crash after claim, transient retry, retry exhaustion/DLQ, and malformed schema. Run all relevant tests and stop at the Phase 3 gate.
```

## Phase 4 prompt — Operations, verification, and CI

```text
Implement Phase 4 only. Re-read TESTING_STRATEGY.md, API_CONTRACT.md, SECURITY_AND_SAFETY.md, DEPLOYMENT_AND_CI.md. Add safe transfer history/attempt APIs, paginated operational views, reconciliation runs/issues, actuator readiness/liveness, structured JSON logs, correlation IDs, bounded-cardinality metrics, and CI. Verify health endpoints do not leak environment/configuration. Add an end-to-end demo walkthrough and run it against fresh dependencies. CI must require relevant integration tests; do not silently skip Testcontainers. Update the docs to describe only verified behavior. Stop before starting React.
```

## Phase 5 prompt — UI after backend gate

```text
Confirm all Phase 1–4 acceptance gates are checked and the backend integration suite passes. Then implement the dashboard according to docs/UI_DASHBOARD.md only. Use React + TypeScript, lock dependencies, use decimal strings for amounts, show the permanent synthetic-simulator warning, add loading/empty/error states and tests, and call documented APIs only. Do not implement financial logic or bypass backend authorization. Do not add unsupported API calls. Run frontend lint/typecheck/test/build and backend regression suite. Update checklist/phase log and stop.
```

## Final audit prompt

```text
Perform a read-only-first audit before making changes. Compare implementation against every invariant in AGENTS.md, DOMAIN_MODEL.md, DATA_MODEL.md, EVENTING_AND_FAILURES.md, TESTING_STRATEGY.md, SECURITY_AND_SAFETY.md, and DEVELOPMENT_CHECKLIST.md. Run the full clean-checkout build and required tests. Inspect Git diff/status for secrets, generated files, TODO stubs, incorrect claims, missing migrations, and code paths that bypass the outbox or ledger transaction. Fix only specific defects with regression tests. Verify README commands. Produce an audit report with evidence, exact commands/counts, remaining gaps, and no unverified claims. Do not mark features complete based solely on code inspection when runtime verification is required.
```

## Review protocol for every agent reply

The human reviewer should request the exact diff and test evidence. Ask follow-up only for blockers or unexplained architecture changes. Specifically review: transaction boundaries, lock order, unique DB constraints, retry transaction atomicity, handling of ambiguous commit/network failures, and whether any test has been weakened to make the build pass.
