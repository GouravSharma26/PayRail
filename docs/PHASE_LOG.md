# Phase Log

> This is an implementation evidence log. Do not fill planned results in advance. Use dates and actual test output only.

## Status at documentation creation

- Documentation pack created: 2026-10-24.
- Implementation status: Phase 0 verified and locked on 2026-10-24.
- Next action: Phase 1 — Core domain, API, migrations, and local runtime.

## Log entries

### 2026-10-24 — Phase 0: Workspace reconnaissance and design lock

- **Commit:** `95301f9`
- **Scope:** Workspace inspection, toolchain verification (Java 21, Docker/Compose, Maven Wrapper), official dependency compatibility verification, repository layout proposal, and recording pinned architectural decisions.
- **Files changed:**
  - `docs/MODEL_AND_TECH_DECISIONS.md` (recorded pinned versions, sources, ADR-009 repository layout, synthetic boundary)
  - `docs/DEVELOPMENT_CHECKLIST.md` (checked off Phase 0 items)
  - `docs/PHASE_LOG.md` (recorded Phase 0 execution evidence)
  - `pom.xml` (root Maven reactor POM with Spring Boot 3.4.3 parent and Java 21)
  - `backend/pom.xml` (backend module POM with Spring Boot 3.4.3 starters, Kafka, Flyway, PostgreSQL driver, OpenAPI, Testcontainers)
  - `.mvn/wrapper/maven-wrapper.properties` (pinned Maven 3.9.9)
  - `mvnw`, `mvnw.cmd` (Maven Wrapper scripts generated)
  - `docker-compose.yml` (pinned postgres:16.8-alpine and apache/kafka:3.8.1 in KRaft mode)
  - `.env.example` (safe placeholder environment variables)
  - `.gitignore` (excludes secrets, credentials, build outputs, and container storage)
  - `.github/workflows/ci.yml` (initial CI workflow for JDK 21 and Maven verify)
- **Design decision(s):**
  - ADR-009: Multi-module Maven setup (`payrail-root` at root containing `backend/` application module; `frontend/` deferred to Phase 5).
  - Selected Spring Boot 3.4.3, Spring Kafka 3.3.3 (kafka-clients 3.8.1), Flyway 10.20.0, PostgreSQL driver 42.7.5, springdoc-openapi 2.8.5, Testcontainers 1.20.5, Micrometer 1.14.4.
  - Selected Docker images: `postgres:16.8-alpine` and `apache/kafka:3.8.1` (KRaft mode).
  - Confirmed synthetic-only simulator scope and non-goals.
- **Commands run:**
  - `git init` — PASS, initialized Git repository in `c:/Gourav Sharma/work/PayRail/.git/`
  - `git status` — PASS, inspected repository status; no existing files overwritten
  - `winget install --id EclipseAdoptium.Temurin.21.JDK --silent ...` — PASS, installed JDK 21.0.12.1+1 LTS
  - `& "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe" -version` — PASS, `openjdk version "21.0.12.1" 2026-08-18 LTS`
  - `mvn -version` — PASS, `Apache Maven 3.9.9`, Java `21.0.12.1`
  - `docker ps` — PASS, Docker engine `29.6.2` operational
  - `docker compose version` — PASS, `Docker Compose version v5.3.1`
  - `docker compose config` — PASS, verified Compose configuration syntax
  - `.\mvnw.cmd -version` — PASS, `Apache Maven 3.9.9`, Java `21.0.12.1`
  - `.\mvnw.cmd -B validate` — PASS, `BUILD SUCCESS` for `payrail-root` and `payrail-backend` in 0.379s
- **Test results:** unit `0 passed / 0 failed` (no code or unit tests in Phase 0); integration `0 passed / 0 failed / 0 blocked`; build result: `BUILD SUCCESS` (POM validation).
- **Acceptance criteria verified:**
  - Proposed repository layout and exact version choices recorded in `pom.xml`, `docker-compose.yml`, and `docs/MODEL_AND_TECH_DECISIONS.md`.
  - Synthetic-only scope and non-goals clearly documented.
  - No application feature claimed implemented; Phase 0 gate reached.
- **Not verified / blocked:** None. All Phase 0 checks succeeded.
- **Known limitations:** Application domain, Flyway migrations, and runtime endpoints are not yet implemented (scheduled for Phase 1).
- **Reviewer notes / follow-up:** Ready for Phase 1 review and gate approval.

### 2026-10-24 — Phase 1: Core domain and REST API

- **Commit:** `382a482`
- **Scope:** Implementation of synthetic accounts, transfer submission, request validation, transaction-state management, durable idempotency, outbox event persistence, Flyway migrations, OpenAPI documentation, and automated unit/integration tests with Testcontainers.
- **Files changed:**
  - `backend/src/main/resources/db/migration/V1__initial_schema.sql` (Authoritative 10-table relational schema with strict check constraints and indexes)
  - `backend/src/main/resources/application.yml` (Structured YAML configuration with correlation ID logging pattern, configurable DB port, Actuator, and OpenAPI)
  - `backend/src/main/resources/application-dev.yml` (Dev profile with deterministic synthetic account seeding enabled)
  - `backend/src/main/resources/application-test.yml` (Test profile with Flyway auto-migrate and test settings)
  - `backend/src/main/java/com/payrail/PayRailApplication.java` (Spring Boot main class)
  - `backend/src/main/java/com/payrail/common/model/ErrorDetail.java` & `ErrorResponse.java` & `PagedResponse.java` (Consistent REST API error and pagination models)
  - `backend/src/main/java/com/payrail/common/exception/*.java` (Domain exceptions with structured error codes: VALIDATION_ERROR, RESOURCE_NOT_FOUND, IDEMPOTENCY_KEY_REUSED, INVALID_TRANSFER, CURRENCY_MISMATCH, ACCOUNT_INACTIVE, INSUFFICIENT_FUNDS)
  - `backend/src/main/java/com/payrail/common/util/AmountParser.java` (Strict scale <= 2 and precision <= 19 parser; strictly avoids float/double)
  - `backend/src/main/java/com/payrail/common/util/CanonicalRequestHasher.java` (Canonical SHA-256 payload hashing and safe key fingerprinting)
  - `backend/src/main/java/com/payrail/common/filter/CorrelationIdFilter.java` (HTTP header X-Correlation-Id tracing and MDC injection)
  - `backend/src/main/java/com/payrail/common/web/GlobalExceptionHandler.java` (Sanitized JSON exception handling avoiding secret leaks and raw stack traces)
  - `backend/src/main/java/com/payrail/account/domain/*` & `dto/*` & `repository/*` & `service/*` & `controller/*` (Account management module)
  - `backend/src/main/java/com/payrail/idempotency/domain/*` & `repository/*` (Durable idempotency persistence)
  - `backend/src/main/java/com/payrail/outbox/domain/*` & `repository/*` (Transactional outbox persistence)
  - `backend/src/main/java/com/payrail/transfer/domain/*` & `dto/*` & `repository/*` & `service/*` & `controller/*` (Transfer submission and query module)
  - `backend/src/main/java/com/payrail/config/OpenApiConfig.java` (OpenAPI 3.0 metadata with synthetic-only notice)
  - `backend/src/main/java/com/payrail/seed/DevDataSeeder.java` (Deterministic synthetic test accounts for local dev)
  - `backend/src/test/java/com/payrail/BaseIntegrationTest.java` (Testcontainers PostgreSQL 16.8 singleton harness with automatic table clearing)
  - `backend/src/test/java/com/payrail/FlywayMigrationIntegrationTest.java` (Flyway migration verification on clean container)
  - `backend/src/test/java/com/payrail/AccountApiIntegrationTest.java` (Account REST API integration tests)
  - `backend/src/test/java/com/payrail/TransferApiIntegrationTest.java` (Transfer API and idempotency integration tests)
  - `backend/src/test/java/com/payrail/common/util/AmountParserTest.java` (Unit tests for monetary amount validation)
  - `backend/src/test/java/com/payrail/common/util/CanonicalRequestHasherTest.java` (Unit tests for canonical hashing and key formatting)
  - `backend/src/test/resources/docker-java.properties` & `testcontainers.properties` (Windows Docker Desktop pipe and API version configuration)
  - `docker-compose.yml` (Added POSTGRES_PORT fallback to avoid local port collisions)
  - `.env.example` (Updated with POSTGRES_PORT and DB_PORT documentation)
  - `docs/DEVELOPMENT_CHECKLIST.md` (Checked off all Phase 1 verification items)
  - `docs/PHASE_LOG.md` (Recorded Phase 1 verification evidence)
- **Design decision(s):**
  - Confirmed Phase 1 transfers start strictly in `PENDING` status. No transfer can be marked `SUCCEEDED` until Phase 2 implements the ledger-posting transaction path.
  - Submissions are atomic across `transfers`, `idempotency_records`, `transfer_status_history`, and `outbox_events` in a single JDBC transaction.
  - Concurrent idempotency key collisions are handled by catching PostgreSQL duplicate key constraint violation on `idempotency_records` and re-reading the committed record.
  - Managed PostgreSQL container in `BaseIntegrationTest` as a singleton lifecycle across all test classes to avoid connection-reset issues.
- **Commands run:**
  - `.\mvnw.cmd test` — PASS, 49 tests run, 0 failures, 0 errors, 0 skipped
  - `.\mvnw.cmd -B verify` — PASS, `BUILD SUCCESS` in 36.757s; packaged `payrail-backend-0.1.0-SNAPSHOT.jar`
  - `docker compose up -d postgres` (with POSTGRES_PORT=5438) — PASS, container `payrail-postgres` started and healthy
  - `java -Dspring.profiles.active=dev -DDB_PORT=5438 -jar backend/target/payrail-backend-0.1.0-SNAPSHOT.jar` — PASS, dev server started in 5.031s and seeded demo accounts
  - `curl.exe --max-time 5 -i http://localhost:8080/actuator/health` — PASS, HTTP 200 OK with `{"status":"UP","groups":["liveness","readiness"]}`
  - `curl.exe --max-time 10 -i -X POST http://localhost:8080/api/v1/transfers` (new transfer) — PASS, HTTP 202 Accepted, transfer in `PENDING` state
  - `curl.exe --max-time 10 -i -X POST http://localhost:8080/api/v1/transfers` (same key + same payload replay) — PASS, HTTP 200 OK returning original transfer
  - `curl.exe --max-time 10 -i -X POST http://localhost:8080/api/v1/transfers` (same key + different payload) — PASS, HTTP 409 Conflict with `IDEMPOTENCY_KEY_REUSED`
  - `curl.exe --max-time 10 -i "http://localhost:8080/api/v1/transfers?page=0&size=10"` — PASS, HTTP 200 OK with paginated transfers list
  - `curl.exe --max-time 10 -i "http://localhost:8080/api/v1/transfers/{id}/history"` — PASS, HTTP 200 OK with transition history
  - `curl.exe --max-time 10 -i "http://localhost:8080/v3/api-docs"` — PASS, HTTP 200 OK with complete OpenAPI 3.0 specification
- **Test results:** unit `33 passed / 0 failed`; integration `16 passed / 0 failed / 0 blocked`; build result: `BUILD SUCCESS` (49 total tests passing).
- **Acceptance criteria verified:**
  - Application builds cleanly from scratch with `./mvnw.cmd`.
  - Flyway migration V1 applies cleanly to an empty PostgreSQL database.
  - Synthetic accounts can be created, read, and paginated.
  - Strict transfer validation prevents negative/zero amounts, excess scale, mismatched currencies, and inactive/non-existent accounts.
  - Idempotent replay produces HTTP 200 with original transfer; key reuse produces HTTP 409 Conflict.
  - No transfer falsely appears `SUCCEEDED` (Phase 1 gate invariant strictly preserved).
- **Not verified / blocked:** Kafka event consumption, ledger postings, and balance mutations (intentionally deferred to Phase 2 and Phase 3).
- **Known limitations:** Transfers currently remain in `PENDING` status awaiting Phase 2 ledger-posting transaction implementation and Phase 3 broker integration.
- **Reviewer notes / follow-up:** Phase 1 complete. Ready for Phase 1 review and gate approval.

### 2026-10-24 — Phase 2: Ledger, Consistency, and Concurrency

- **Commit:** pending commit
- **Scope:** Implementation of immutable double-entry ledger, atomic transfer posting service, deterministic row locking for deadlock avoidance, balance projection maintenance, Flyway V2 immutability triggers, continuous reconciliation audit service, and high-concurrency race condition and deadlock elimination tests.
- **Files changed:**
  - `backend/src/main/resources/db/migration/V2__ledger_immutability_and_triggers.sql` (PostgreSQL triggers preventing UPDATE or DELETE on ledger_entries and transfer_status_history, plus composite index)
  - `backend/src/main/java/com/payrail/account/repository/AccountRepository.java` & `JdbcAccountRepository.java` (Added `findByIdForUpdate` and `updateBalance`)
  - `backend/src/main/java/com/payrail/transfer/repository/TransferRepository.java` & `JdbcTransferRepository.java` (Added `findByIdForUpdate` and `updateStatus`)
  - `backend/src/main/java/com/payrail/transfer/controller/TransferController.java` (Added `POST /api/v1/transfers/{transferId}/process` endpoint for posting invocation)
  - `backend/src/main/java/com/payrail/ledger/domain/LedgerLeg.java` & `LedgerEntryRecord.java` (Immutable double-entry ledger domain models)
  - `backend/src/main/java/com/payrail/ledger/repository/LedgerRepository.java` & `JdbcLedgerRepository.java` (Append-only ledger persistence; strictly lacks update/delete methods)
  - `backend/src/main/java/com/payrail/ledger/service/PostingFailureInjector.java` (Hook for testing atomic transaction rollbacks)
  - `backend/src/main/java/com/payrail/ledger/service/TransferPostingResult.java` (Posting outcome representation)
  - `backend/src/main/java/com/payrail/ledger/service/TransferPostingService.java` (Transactional posting engine with deterministic UUID locking)
  - `backend/src/main/java/com/payrail/ledger/service/ReconciliationReport.java` & `ReconciliationService.java` (Continuous balance and ledger audit engine)
  - `backend/src/main/java/com/payrail/ledger/dto/LedgerEntryResponse.java` (REST DTO for ledger inspection)
  - `backend/src/main/java/com/payrail/ledger/controller/LedgerController.java` (REST endpoints for transfer and account ledger inspection)
  - `backend/src/test/java/com/payrail/BaseIntegrationTest.java` (Updated `cleanDatabase` to use TRUNCATE CASCADE, avoiding row-level delete trigger conflicts)
  - `backend/src/test/java/com/payrail/FlywayMigrationIntegrationTest.java` (Verified V1 and V2 migrations and PostgreSQL triggers)
  - `backend/src/test/java/com/payrail/LedgerPostingIntegrationTest.java` (Integration tests for balanced posting, insufficient funds, inactive accounts, currency mismatch, atomic rollback, duplicate posting idempotence, and immutability triggers)
  - `backend/src/test/java/com/payrail/LedgerConcurrencyIntegrationTest.java` (High-concurrency tests for competing overspend and bidirectional deadlock elimination)
  - `docs/DEVELOPMENT_CHECKLIST.md` (Checked off Phase 2 items)
  - `docs/MODEL_AND_TECH_DECISIONS.md` (Recorded ADR-010 on deterministic locking and double-entry invariants)
  - `docs/PHASE_LOG.md` (Recorded Phase 2 verification evidence)
- **Design decision(s):**
  - ADR-010: Transfers post atomically inside `@Transactional(isolation = Isolation.READ_COMMITTED)`. Row locks on accounts via `SELECT ... FOR UPDATE` are acquired in deterministic ascending UUID order (`firstLock = min(src, dst)`, `secondLock = max(src, dst)`), eliminating deadlock hazards under concurrent bidirectional transfers.
  - Immutability enforced by PostgreSQL trigger `trg_prevent_ledger_update_or_delete` preventing `UPDATE` and `DELETE` on `ledger_entries`.
  - Non-negative balances enforced by database check constraint `CHECK (current_balance >= 0)`.
  - Reconciliation service verifies `current_balance == opening_balance - SUM(debits) + SUM(credits)` across all accounts.
- **Commands run:**
  - `.\mvnw.cmd test` — PASS, 62 tests run, 0 failures, 0 errors, 0 skipped
  - `.\mvnw.cmd -B verify` — PASS, `BUILD SUCCESS` in 18.863s
- **Test results:** unit `33 passed / 0 failed`; integration `29 passed / 0 failed / 0 blocked`; build result: `BUILD SUCCESS` (62 total tests passing).
- **Acceptance criteria verified:**
  - All existing Phase 1 tests pass without regression.
  - Every successfully posted transfer has exactly one balanced debit/credit pair.
  - Failed postings (insufficient funds, inactive accounts, currency mismatch) leave no partial financial changes and zero ledger entries.
  - Concurrent transfers cannot overspend the same account (10 parallel contender threads on 100.00 INR balance resulted in exactly 5 successes and 5 insufficient funds declines).
  - Bidirectional concurrent transfers between accounts complete without deadlock.
  - Duplicate posting attempts cannot create duplicate ledger entries.
  - Account-balance consistency invariant is verified via reconciliation queries.
  - Clean Maven build and package succeed.
- **Not verified / blocked:** Asynchronous Kafka message publishing, outbox poller lease recovery, and consumer message deduplication (scheduled for Phase 3).
- **Known limitations:** Outbox event records are persisted at transfer submission but background asynchronous polling and Kafka publishing will be wired in Phase 3.
- **Reviewer notes / follow-up:** Phase 2 complete. Ready for Phase 2 review and gate approval.

## Entry template

### YYYY-MM-DD — Phase N: short name

- **Commit:** `<short SHA>` or `not committed`
- **Scope:**
- **Files changed:**
- **Design decision(s):**
- **Commands run:**
  - `command` — PASS/FAIL/BLOCKED, exact reason/output summary
- **Test results:** unit `n passed / n failed`; integration `n passed / n failed / n blocked`; build result.
- **Acceptance criteria verified:**
- **Not verified / blocked:**
- **Known limitations:**
- **Reviewer notes / follow-up:**

## Required evidence

- The actual command, not “tests passed”.
- Observed test result/count; don't guess counts.
- Explicitly call out Docker/network/tooling blockers.
- Do not change prior entries to hide regression history; append corrections with dates.
- If a CI job ran, include the workflow/job name and commit SHA; never fabricate a run URL.
