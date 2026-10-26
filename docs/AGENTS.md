# PayRail — Agent Instructions

These rules apply to every coding session in this repository. Follow them before editing code.

## Mission and execution style

- Build PayRail as a **synthetic-only simulator**. No real banking, payment rails, card processing, financial credentials, or real user data.
- Treat `plan.md` as the phase source of truth and `docs/*.md` as contracts. Read the files relevant to the phase before coding.
- Work in small, reviewable increments. Complete only the requested phase; do not jump ahead to the frontend or optional features.
- Before editing, inspect the existing repository and summarize its current state. Preserve working code. Never overwrite existing work without inspecting it.
- When a choice is material and not specified, propose one default, record it in `docs/MODEL_AND_TECH_DECISIONS.md`, and proceed. Do not silently change major architectural decisions.
- Do not claim a test passed unless you ran it and observed the result. Do not claim deployment works unless it was actually verified.

## Non-negotiable invariants

1. PostgreSQL is authoritative for money-like state. Do not keep authoritative balances in memory, Kafka, or the frontend.
2. Amounts must never use binary floating point. Use `BigDecimal` with explicit scale validation and PostgreSQL `NUMERIC(19,2)`; serialize amounts as decimal strings.
3. Every completed transfer has exactly one debit and one credit ledger entry, equal in amount and currency, and both entries and the balance updates are committed atomically.
4. A transfer must never make an account balance negative. Enforce this with transaction-level logic, row locks, and database constraints where feasible.
5. Duplicate `Idempotency-Key` + identical canonical request returns the original transfer. Reusing the key with a different request returns `409 IDEMPOTENCY_KEY_REUSED`.
6. Transfer state and the request outbox event are written in the same database transaction. Never implement “commit transfer, then publish directly to Kafka” as the only event-delivery path.
7. Assume Kafka delivery can repeat. Consumer handling, ledger postings, and retry scheduling must be idempotent. Do not describe the whole system as exactly-once.
8. Ledger rows are append-only. Never edit or delete a committed ledger entry to “fix” reconciliation. Reconciliation detects; it does not silently repair.
9. Validate state transitions on the server. Terminal `SUCCEEDED` and `FAILED` transfers cannot be processed again.
10. No credentials or secrets committed to Git. Use environment variables and `.env.example` with placeholders only.

## Required engineering practices

- Java 21; use the Maven Wrapper and pin compatible dependency/image versions. Avoid floating `latest` container tags.
- Use Flyway migrations for schema changes. Never make schema changes by relying on Hibernate/JPA auto-DDL.
- Keep controllers thin. Put validation/orchestration in services and SQL/locking in persistence code. Separate API DTOs from database rows.
- Prefer explicit SQL/JDBC for the critical locking and ledger path so transaction boundaries and `SELECT ... FOR UPDATE` behavior are visible. Do not mix JDBC and ORM writes to the same entities without an explicit reason.
- Make transaction boundaries explicit with `@Transactional` or clearly scoped transaction templates. Understand proxy/self-invocation limitations before relying on annotations.
- Use UTC `Instant` timestamps, UUID identifiers, structured logs, stable machine-readable error codes, and correlation IDs.
- Never log secrets, full idempotency keys, sensitive payloads, or unbounded stack traces in normal request logs. Log a safe key fingerprint if correlation is needed.
- Keep API responses deterministic and document error semantics in `docs/API_CONTRACT.md`.
- Add tests with every behavior. Critical race/failure behavior must be covered by integration tests against real PostgreSQL and the broker via Testcontainers, not only mocks.

## Phase execution protocol

For each requested phase:

1. Read the relevant docs and inspect current code/status.
2. State the intended change and acceptance checks in a brief plan.
3. Implement the smallest coherent slice.
4. Run formatter/linter if configured, unit tests, integration tests relevant to the change, and a package/build.
5. Fix all failures introduced by the change. Do not hide failures by disabling assertions or weakening tests.
6. Review the diff for security, data integrity, backward compatibility, and accidental files/secrets.
7. Update `docs/DEVELOPMENT_CHECKLIST.md` and `docs/PHASE_LOG.md` with only verified facts.
8. Report files changed, commands run, pass/fail counts, any unrun checks, and known limitations.

If a check cannot run because of missing Docker/network/tooling, say exactly which check is blocked and run all feasible checks instead. Do not mark the phase complete until its acceptance gate in `plan.md` is met.

## Avoid

- Building the entire application in one large code-generation pass.
- Adding microservices, Kubernetes, Redis, CQRS frameworks, distributed transactions, payment-provider integrations, or elaborate authentication before the baseline requires them.
- A test-only fake implementation being accidentally wired into normal runtime.
- Catching all exceptions and returning success, swallowing broker failures, retrying permanent business declines, or marking a transfer successful before ledger and balance writes commit.
- “Repair” code that modifies ledger history or invents a success after ambiguous failures.
- Large generated files, dead code, TODO-only implementations, commented-out tests, or documentation that promises features not implemented.

## Read before implementation

- Roadmap: `plan.md`
- Architecture: `docs/ARCHITECTURE.md`
- Domain contracts: `docs/DOMAIN_MODEL.md`
- Database rules: `docs/DATA_MODEL.md`
- Public API: `docs/API_CONTRACT.md`
- Broker and recovery semantics: `docs/EVENTING_AND_FAILURES.md`
- Verification: `docs/TESTING_STRATEGY.md`
- Safety: `docs/SECURITY_AND_SAFETY.md`
