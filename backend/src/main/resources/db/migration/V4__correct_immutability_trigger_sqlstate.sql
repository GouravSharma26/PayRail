-- ============================================================================
-- PayRail Schema Migration V4: Correct Immutability Trigger SQLSTATE
-- Updates immutability triggers to raise check_violation (23514)
-- ============================================================================

CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are strictly append-only and immutable. UPDATE or DELETE operations are prohibited.'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_status_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Transfer status history is strictly append-only and immutable. UPDATE or DELETE operations are prohibited.'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
