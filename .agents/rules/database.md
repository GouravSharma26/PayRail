---
trigger: model_decision
description: Apply when creating or reviewing PostgreSQL schema, migrations, SQL locking, ledger postings, or outbox persistence.
---

# Database and Ledger Rules

- PostgreSQL is authoritative. Flyway owns schema creation/evolution; never use ORM auto-DDL to mutate schema.
- Use parameterized SQL and UTC timestamps. Amounts use `BigDecimal` and `NUMERIC(19,2)`; never binary float.
- Money-like state must not be managed using process-local locks or caches.
- Lock a transfer and then both account rows in deterministic account-ID order; use database constraints as the final defense.
- Balance updates, debit/credit entries, transfer terminal state, status history, and event dedupe must commit atomically.
- Ledger rows are append-only. Never implement ledger update/delete or silent reconciliation repair.
- Outbox event creation shares the transaction that creates the transfer. Publisher locks are short-lived; publish outside DB transactions and mark published only after acknowledgment.
- Add/check unique constraints for idempotency, transfer/ledger leg, aggregate event sequence, and processed event IDs.
- Add tests for rollback between posting steps, concurrent overspend, and duplicate attempts.
- Never alter an applied migration; add a new migration and test fresh + upgrade paths.
