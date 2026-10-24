-- ============================================================================
-- PayRail Schema Migration V2: Ledger and Audit Immutability Triggers
-- Enforces append-only guarantees at the PostgreSQL engine level
-- ============================================================================

-- 1. Prevent updates or deletions on ledger_entries
CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are strictly append-only and immutable. UPDATE or DELETE operations are prohibited.'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_ledger_update_or_delete
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_mutation();

-- 2. Prevent updates or deletions on transfer_status_history
CREATE OR REPLACE FUNCTION prevent_status_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Transfer status history is strictly append-only and immutable. UPDATE or DELETE operations are prohibited.'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_status_history_update_or_delete
BEFORE UPDATE OR DELETE ON transfer_status_history
FOR EACH ROW
EXECUTE FUNCTION prevent_status_history_mutation();

-- 3. Composite index for high-speed reconciliation queries
CREATE INDEX idx_ledger_entries_account_leg_amount ON ledger_entries (account_id, leg, amount);
