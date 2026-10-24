# PostgreSQL Data Model and Migration Rules

## General

Use PostgreSQL as the durable source of truth. Use UUID primary keys, `TIMESTAMPTZ` UTC timestamps, explicit foreign keys, check constraints, indexes, and Flyway migrations. Prefer explicit JDBC SQL for lock-sensitive operations. Never use Hibernate schema auto-generation to mutate the database. A migration is part of the code review and must be tested against an empty database.

Use names from this document as a logical contract; small naming changes are allowed if all docs/tests remain consistent.

## Tables

### `accounts`

Suggested columns:

- `id UUID PRIMARY KEY`
- `display_name VARCHAR(80) NOT NULL`
- `currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$')`
- `opening_balance NUMERIC(19,2) NOT NULL CHECK (opening_balance >= 0)`
- `current_balance NUMERIC(19,2) NOT NULL CHECK (current_balance >= 0)`
- `status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE'))`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`

Treat `opening_balance` as immutable after account creation. Ensure all opening/current amount operations use exact decimal arithmetic. Add indexes for common account lookup/status queries only when used.

### `transfers`

Suggested columns:

- `id UUID PRIMARY KEY`
- `source_account_id UUID NOT NULL REFERENCES accounts(id)`
- `destination_account_id UUID NOT NULL REFERENCES accounts(id)`
- `amount NUMERIC(19,2) NOT NULL CHECK (amount > 0)`
- `currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$')`
- `status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED'))`
- `failure_code VARCHAR(64)`
- `failure_message VARCHAR(300)`
- `correlation_id UUID NOT NULL`
- `processing_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (processing_attempt_count >= 0)`
- `processing_claim_token UUID`
- `processing_lease_until TIMESTAMPTZ`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `completed_at TIMESTAMPTZ`
- `CHECK (source_account_id <> destination_account_id)`
- `CHECK ((status IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL) OR (status IN ('PENDING','PROCESSING') AND completed_at IS NULL))`

The service must verify currency equals both account currencies in the posting transaction. Cross-table currency checks cannot be expressed safely as a simple SQL CHECK, so enforce in service transaction and reconciliation.

Indexes: `(created_at DESC, id)`, `(status, created_at)`, `(source_account_id, created_at DESC)`, `(destination_account_id, created_at DESC)`. Evaluate indexes with representative queries; avoid redundant indexes.

### `idempotency_records`

- `idempotency_key VARCHAR(128) PRIMARY KEY` (or use a scoped composite key if authentication/client scoping is later added)
- `request_hash CHAR(64) NOT NULL`
- `transfer_id UUID NOT NULL UNIQUE REFERENCES transfers(id)`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`

Keys must be validated for length and permitted characters. Do not log raw keys. Compute the hash from a canonical normalized request containing source ID, destination ID, amount normalized to two decimals, currency, and API contract version. Do not include mutable server-generated fields or request timestamps. Handle duplicate-key races with the unique constraint and re-read; an in-memory “check then insert” is not sufficient.

### `ledger_entries`

- `id UUID PRIMARY KEY`
- `transfer_id UUID NOT NULL REFERENCES transfers(id)`
- `account_id UUID NOT NULL REFERENCES accounts(id)`
- `leg VARCHAR(8) NOT NULL CHECK (leg IN ('DEBIT','CREDIT'))`
- `amount NUMERIC(19,2) NOT NULL CHECK (amount > 0)`
- `currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$')`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `UNIQUE (transfer_id, leg)`

Add `(account_id, created_at, id)` and `(transfer_id)` indexes as needed. The DB unique constraint guarantees at most one debit and credit per transfer; service transaction + reconciliation proves exactly two for a successful transfer and zero for failed/pending transfers. Do not create update/delete repository methods for ledger entries. Add database permissions/triggers only if useful for the local design; at minimum enforce append-only behavior in the application layer and test it.

### `transfer_status_history`

- `id BIGSERIAL PRIMARY KEY` (or UUID)
- `transfer_id UUID NOT NULL REFERENCES transfers(id)`
- `from_status VARCHAR(16)` (NULL only for initial transition)
- `to_status VARCHAR(16) NOT NULL`
- `reason_code VARCHAR(64) NOT NULL`
- `attempt_number INTEGER`
- `correlation_id UUID NOT NULL`
- `occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()`

Index `(transfer_id, occurred_at, id)`. Append-only. Status history insertion and status update must share the same transaction.

### `processing_attempts`

- `id UUID PRIMARY KEY`
- `transfer_id UUID NOT NULL REFERENCES transfers(id)`
- `attempt_number INTEGER NOT NULL CHECK (attempt_number > 0)`
- `claim_token UUID NOT NULL`
- `outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('IN_PROGRESS','SUCCEEDED','BUSINESS_FAILED','RETRY_SCHEDULED','EXHAUSTED'))`
- `error_code VARCHAR(64)`
- `safe_error_message VARCHAR(300)`
- `started_at TIMESTAMPTZ NOT NULL`
- `finished_at TIMESTAMPTZ`
- `duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0)`
- `UNIQUE (transfer_id, attempt_number)`

### `outbox_events`

- `event_id UUID PRIMARY KEY`
- `aggregate_type VARCHAR(40) NOT NULL`
- `aggregate_id UUID NOT NULL`
- `aggregate_sequence BIGINT NOT NULL`
- `event_type VARCHAR(100) NOT NULL`
- `schema_version INTEGER NOT NULL CHECK (schema_version > 0)`
- `payload JSONB NOT NULL`
- `correlation_id UUID NOT NULL`
- `available_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `published_at TIMESTAMPTZ`
- `publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0)`
- `lease_token UUID`
- `lease_until TIMESTAMPTZ`
- `last_error_code VARCHAR(64)`
- `last_error_message VARCHAR(300)`
- `UNIQUE (aggregate_type, aggregate_id, aggregate_sequence)`

Index partial on due unpublished events, e.g. `(available_at, created_at)` WHERE `published_at IS NULL`; confirm query plan. Use a lease token/expiry to coordinate multiple publisher instances. Claim rows in a short transaction with `FOR UPDATE SKIP LOCKED`; publish outside DB transaction; after broker ack, mark published only when the lease token still matches. A crash after publish but before marking can produce a duplicate, which is expected.

### `processed_messages`

- `consumer_name VARCHAR(100) NOT NULL`
- `event_id UUID NOT NULL`
- `processed_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `outcome VARCHAR(24) NOT NULL`
- `PRIMARY KEY (consumer_name, event_id)`

Write the dedupe record in the same transaction as the durable outcome / retry scheduling. For an in-flight/busy claim, do not mark a message complete if durable recovery has not been arranged. Duplicate message attempts should be safe.

### `reconciliation_runs` and `reconciliation_issues`

`reconciliation_runs`: `id UUID`, start/finish times, status, scanned counts, issue count, trigger (`SCHEDULED`/`MANUAL`), and safe summary.

`reconciliation_issues`: `id UUID`, `run_id UUID`, issue type/code, severity, optional account/transfer/outbox IDs, detection timestamp, observed values encoded as safe JSONB, status (`OPEN`/`ACKNOWLEDGED`/`RESOLVED`), and resolution note. Resolving an issue is an operator annotation, not automatic money-state modification. Do not store secrets or unbounded dumps in JSONB.

## Money / currency rules

- API and JSON serialize amounts as strings, for example `"125.40"`.
- Java uses `BigDecimal`; validate scale exactly 2 and precision <= 19 before writing.
- Never use `double`, `float`, or JavaScript floating point for authoritative amount arithmetic.
- No currency conversion. Reject transfer unless both accounts and the requested transfer have the same currency.
- Never round excess decimal precision silently; return a stable validation error.

## Locking and concurrent writes

- For posting, lock the transfer row and account rows in deterministic ID order to reduce deadlocks.
- Enforce adequate JDBC transaction isolation and retry known transient SQL/deadlock conditions only through the bounded retry workflow.
- Correctness comes from DB locks and constraints, not JVM `synchronized`, `ReentrantLock`, or an in-memory map.
- Use short transactions. Avoid calling Kafka or the frontend from inside account/ledger transactions.

## Migration discipline

- Use numbered Flyway migrations, e.g. `V1__core_tables.sql`.
- Never edit an already-applied migration to change a shared schema; add a new migration.
- Test migration from an empty database and upgrade from the prior schema state.
- Any destructive/renaming change should use expand-and-contract where practical and be documented.
- Never use `ddl-auto=create`, `update`, or schema recreation against a developer's persistent database.
