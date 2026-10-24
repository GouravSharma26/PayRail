-- ============================================================================
-- PayRail Schema Migration V1: Initial Database Schema
-- Authoritative PostgreSQL Schema for Synthetic Payment Simulator
-- ============================================================================

-- 1. Accounts Table
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    opening_balance NUMERIC(19,2) NOT NULL CHECK (opening_balance >= 0),
    current_balance NUMERIC(19,2) NOT NULL CHECK (current_balance >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_status ON accounts (status);
CREATE INDEX idx_accounts_currency ON accounts (currency);

-- 2. Transfers Table
CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    failure_code VARCHAR(64),
    failure_message VARCHAR(300),
    correlation_id UUID NOT NULL,
    processing_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (processing_attempt_count >= 0),
    processing_claim_token UUID,
    processing_lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT chk_transfers_status_completed CHECK (
        (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL) OR
        (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL)
    )
);

CREATE INDEX idx_transfers_created_at_id ON transfers (created_at DESC, id DESC);
CREATE INDEX idx_transfers_status_created_at ON transfers (status, created_at DESC);
CREATE INDEX idx_transfers_source_created_at ON transfers (source_account_id, created_at DESC);
CREATE INDEX idx_transfers_destination_created_at ON transfers (destination_account_id, created_at DESC);
CREATE INDEX idx_transfers_correlation_id ON transfers (correlation_id);

-- 3. Idempotency Records Table
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    transfer_id UUID NOT NULL UNIQUE REFERENCES transfers(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_records_transfer_id ON idempotency_records (transfer_id);

-- 4. Ledger Entries Table (Append-only)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    leg VARCHAR(8) NOT NULL CHECK (leg IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_entries_transfer_leg UNIQUE (transfer_id, leg)
);

CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC, id);
CREATE INDEX idx_ledger_entries_transfer_id ON ledger_entries (transfer_id);

-- 5. Transfer Status History Table (Append-only)
CREATE TABLE transfer_status_history (
    id BIGSERIAL PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    attempt_number INTEGER,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfer_status_history_transfer ON transfer_status_history (transfer_id, occurred_at ASC, id ASC);

-- 6. Processing Attempts Table
CREATE TABLE processing_attempts (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    claim_token UUID NOT NULL,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('IN_PROGRESS', 'SUCCEEDED', 'BUSINESS_FAILED', 'RETRY_SCHEDULED', 'EXHAUSTED')),
    error_code VARCHAR(64),
    safe_error_message VARCHAR(300),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT uq_processing_attempts_transfer_attempt UNIQUE (transfer_id, attempt_number)
);

CREATE INDEX idx_processing_attempts_transfer_id ON processing_attempts (transfer_id, attempt_number);

-- 7. Outbox Events Table
CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(300),
    CONSTRAINT uq_outbox_events_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, aggregate_sequence)
);

CREATE INDEX idx_outbox_events_due ON outbox_events (available_at, created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);

-- 8. Processed Messages Table (Consumer Deduplication)
CREATE TABLE processed_messages (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome VARCHAR(24) NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

-- 9. Reconciliation Runs & Issues Tables
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL,
    scanned_transfers_count BIGINT NOT NULL DEFAULT 0,
    scanned_accounts_count BIGINT NOT NULL DEFAULT 0,
    issue_count INTEGER NOT NULL DEFAULT 0,
    trigger_type VARCHAR(24) NOT NULL,
    summary VARCHAR(500)
);

CREATE TABLE reconciliation_issues (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
    issue_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    account_id UUID,
    transfer_id UUID,
    outbox_event_id UUID,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    details JSONB NOT NULL,
    status VARCHAR(24) NOT NULL,
    resolution_note VARCHAR(500)
);

CREATE INDEX idx_reconciliation_issues_run_id ON reconciliation_issues (run_id);
CREATE INDEX idx_reconciliation_issues_status ON reconciliation_issues (status);
