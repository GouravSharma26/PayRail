# Model and Technology Decisions

Record actual final choices and verification date during Phase 0. These are proposed defaults, not proof that code is implemented.

## ADR-001 — Synthetic-only simulation
**Decision:** Fictional internal transfers only; no bank/provider integration, real money, FX, or real customer data. **Reason:** Focus on backend reliability and avoid implying production financial capabilities. **Consequence:** README/API/UI must label outcomes as simulated.

## ADR-002 — Modular monolith first
**Decision:** One Spring Boot deployable with capability-oriented packages and one PostgreSQL database; publisher, consumer and schedulers can run in the same app for MVP. **Reason:** Keeps setup simple while demonstrating event-driven design. **Consequence:** No microservices unless an ADR justifies them.

## ADR-003 — PostgreSQL is authoritative
**Decision:** Idempotency, transfer state, account balances, ledger, outbox, consumer dedupe and reconciliation records live in PostgreSQL. **Consequence:** No in-memory authoritative balances.

## ADR-004 — Explicit JDBC in critical path
**Decision:** Prefer Spring JDBC/NamedParameterJdbcTemplate for row locks, ledger posting, outbox leases and reconciliation. **Reason:** SQL and transaction boundaries remain explicit. **Consequence:** Parameterize SQL and avoid mixed ORM/JDBC writes without justification.

## ADR-005 — Outbox + at-least-once handling
**Decision:** The initial transfer and outbox event are committed together. Consumer uses deduplication, unique ledger legs and transfer-state guards. **Consequence:** Duplicate publication/delivery is expected and must be tested; never claim whole-system exactly-once delivery.

## ADR-006 — Opening balance is a synthetic baseline
**Decision:** Account creation can include an immutable synthetic opening balance; subsequent transfer movements are ledger-posted. Reconciliation checks `current_balance = opening_balance + credits - debits`. **Consequence:** State this simplification; do not label baseline as an external deposit.

## ADR-007 — Two-decimal single-currency MVP
**Decision:** Amounts use scale <= 2, no FX; source, destination, and transfer currencies must match. **Consequence:** Use `BigDecimal`, PostgreSQL `NUMERIC(19,2)`, and decimal strings in JSON.

## ADR-008 — UI comes after correctness
**Decision:** Backend ledger/eventing/recovery tests precede React dashboard work. **Consequence:** No frontend scaffold during Phases 0–4 unless a documented scope change requires it.

## ADR-009 — Repository layout and module boundary
**Decision:** Multi-module Maven setup with root POM `payrail-root` (`packaging: pom`), Maven Wrapper at root, and `backend` module (`packaging: jar`) containing the Spring Boot application. Frontend is deferred to Phase 5 in `frontend/`.
**Reason:** Running `.\mvnw.cmd -B verify` or `./mvnw -B verify` from the repository root builds and verifies the entire project. Keeping `backend/` isolated ensures clean separation between Java backend code, Flyway migrations, and the eventual React frontend without polluting the workspace root.
**Consequence:** Commands can be run uniformly from the repo root. Root `docker-compose.yml` orchestrates backend dependencies (PostgreSQL and Kafka).

## ADR-010 — Deterministic locking, transactional balance projections, and double-entry ledger invariants
**Decision:**
1. Transfers are posted atomically inside an explicit database transaction (`@Transactional(isolation = Isolation.READ_COMMITTED)`).
2. To eliminate deadlock hazards under concurrent bidirectional transfers (e.g. A -> B and B -> A simultaneously), row locks on accounts via `SELECT ... FOR UPDATE` are strictly acquired in deterministic lexicographical order of account UUIDs (`firstLock = min(src, dst)`, `secondLock = max(src, dst)`).
3. Stored `current_balance` on `accounts` is maintained as a transactionally updated projection guarded by a database check constraint (`current_balance >= 0`). Every successful transfer debits the sender, credits the recipient, inserts exactly one balanced DEBIT/CREDIT pair in `ledger_entries`, and transitions the transfer to `SUCCEEDED` atomically.
4. If available balance is insufficient, the transfer transitions to `FAILED` with failure code `INSUFFICIENT_FUNDS`, recording a status transition history entry, while writing zero ledger entries and making zero balance modifications.
5. Ledger rows are strictly immutable and append-only, enforced by database triggers preventing `UPDATE` and `DELETE` on `ledger_entries`.
6. Account balances and ledger entries are continuously auditable via reconciliation queries verifying `current_balance = opening_balance - SUM(debits) + SUM(credits)`.

## Exact version record (verified 2026-10-24)

| Component | Pinned version/tag | Compatibility verified against | Official source | Date |
|---|---|---|---|---|
| JDK | Java 21 (Temurin 21.0.12.1+1 LTS) | Spring Boot 3.4.3 baseline (supports Java 17-23), Maven 3.9.9 | https://adoptium.net/ / https://docs.oracle.com/en/java/javase/21/ | 2026-10-24 |
| Spring Boot | `3.4.3` | Java 21, Spring Kafka 3.3.3, Flyway 10.20.0, Testcontainers 1.20.5 | https://docs.spring.io/spring-boot/ | 2026-10-24 |
| Spring Kafka | `3.3.3` (kafka-clients `3.8.1`) | Managed by Spring Boot 3.4.3 BOM; wire-compatible with Apache Kafka 3.8.1 | https://docs.spring.io/spring-kafka/reference/ | 2026-10-24 |
| Flyway | `10.20.0` (`flyway-core` + `flyway-database-postgresql`) | Managed by Spring Boot 3.4.3 BOM; supports PostgreSQL 16.x | https://documentation.red-gate.com/flyway | 2026-10-24 |
| PostgreSQL JDBC Driver | `42.7.5` (`org.postgresql:postgresql`) | Managed by Spring Boot 3.4.3 BOM; compatible with PostgreSQL 16/17 server | https://jdbc.postgresql.org/ | 2026-10-24 |
| OpenAPI Library | `springdoc-openapi-starter-webmvc-ui:2.8.5` | Tested and designed for Spring Boot 3.4 and Spring Framework 6.2 | https://springdoc.org/ | 2026-10-24 |
| Testcontainers | `1.20.5` (`junit-jupiter`, `postgresql`, `kafka`) | Managed by Spring Boot 3.4.3 BOM / `testcontainers-bom:1.20.5` | https://java.testcontainers.org/ | 2026-10-24 |
| Micrometer / Actuator | `1.14.4` (`micrometer-registry-prometheus`) | Managed by Spring Boot 3.4.3 BOM | https://micrometer.io/docs | 2026-10-24 |
| Maven Wrapper | `3.9.9` (wrapper plugin 3.3.4, only-script) | Apache Maven 3.9.9 official binary release, Java 21 | https://maven.apache.org/wrapper/ | 2026-10-24 |
| PostgreSQL Docker image | `postgres:16.8-alpine` | Pinned exact minor/patch image; compatible with Flyway 10.20 and JDBC 42.7.5 | https://hub.docker.com/_/postgres | 2026-10-24 |
| Kafka broker Docker image | `apache/kafka:3.8.1` (KRaft mode) | Official Apache Kafka 3.8.1 KRaft image; matches kafka-clients 3.8.1 in Spring Kafka 3.3.3 (Redpanda `redpandadata/redpanda:v24.3.4` verified alternative) | https://hub.docker.com/r/apache/kafka | 2026-10-24 |
| Docker Engine & Compose | Docker `29.6.2`, Docker Compose `v5.3.1` | Local development environment on Windows 11 with WSL2 engine | https://docs.docker.com/ | 2026-10-24 |

## Proposed repository tree

```text
payrail/
├── .agents/
│   └── rules/
│       ├── database.md
│       ├── java.md
│       └── testing.md
├── .github/
│   └── workflows/
│       └── ci.yml
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── docs/
│   ├── ANTIGRAVITY_WORKFLOW.md
│   ├── API_CONTRACT.md
│   ├── ARCHITECTURE.md
│   ├── DATA_MODEL.md
│   ├── DEPLOYMENT_AND_CI.md
│   ├── DEVELOPMENT_CHECKLIST.md
│   ├── DOMAIN_MODEL.md
│   ├── EVENTING_AND_FAILURES.md
│   ├── HUMAN_SETUP.md
│   ├── MODEL_AND_TECH_DECISIONS.md
│   ├── OFFICIAL_REFERENCES.md
│   ├── PHASE_LOG.md
│   ├── SECURITY_AND_SAFETY.md
│   ├── TESTING_STRATEGY.md
│   └── UI_DASHBOARD.md
├── backend/
│   ├── pom.xml
│   └── src/                 # Added in Phase 1 (no empty stubs in Phase 0)
├── .env.example
├── .gitignore
├── AGENTS.md
├── docker-compose.yml
├── mvnw
├── mvnw.cmd
├── plan.md
├── pom.xml
└── README.md
```

## Synthetic-only confirmation and non-goals

- **Synthetic-only boundary:** PayRail is strictly an educational/demonstration payment simulator. All accounts, account balances, transfers, and ledger entries are synthetic data generated locally or in tests.
- **Strict non-goals:**
  1. No real bank, card network, payment rail, payment gateway, or financial provider integrations.
  2. No storage or ingestion of real financial credentials, card numbers, CVVs, bank account numbers, or personal identifying information (PII).
  3. No foreign exchange (FX) conversion or multi-currency transfers; source, destination, and transfer currencies must match.
  4. No floating-point financial arithmetic; all money calculations use exact `BigDecimal` with scale 2.
  5. No in-memory authoritative financial state; PostgreSQL is the sole authoritative source of truth.

## Deferred
Public-demo authentication, multi-currency precision/FX, fees, reversals/holds, separate worker deployment, schema registry, Kubernetes, and production operations. Do not implement deferred scope without updated acceptance criteria.
