-- ============================================================================
-- PayRail Schema Migration V3: PostgreSQL Deferred Constraint Triggers
-- Enforces double-entry ledger invariants at database transaction commit
-- ============================================================================

CREATE OR REPLACE FUNCTION check_transfer_ledger_invariants()
RETURNS TRIGGER AS $$
DECLARE
    v_transfer_id UUID;
    v_transfer RECORD;
    v_entry_count INTEGER;
    v_debit RECORD;
    v_credit RECORD;
BEGIN
    -- Determine which transfer_id was affected
    IF TG_TABLE_NAME = 'transfers' THEN
        v_transfer_id := NEW.id;
    ELSIF TG_TABLE_NAME = 'ledger_entries' THEN
        v_transfer_id := NEW.transfer_id;
    END IF;

    -- Fetch the final state of the transfer at transaction commit
    SELECT * INTO v_transfer FROM transfers WHERE id = v_transfer_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    -- Check invariants based on transfer status
    IF v_transfer.status = 'SUCCEEDED' THEN
        -- 1. Exactly 2 entries must exist
        SELECT COUNT(*) INTO v_entry_count FROM ledger_entries WHERE transfer_id = v_transfer_id;
        IF v_entry_count <> 2 THEN
            RAISE EXCEPTION 'Ledger invariant violation: SUCCEEDED transfer % must have exactly 2 ledger entries, found %',
                v_transfer_id, v_entry_count
                USING ERRCODE = '23514';
        END IF;

        -- 2. Validate DEBIT entry
        SELECT * INTO v_debit FROM ledger_entries WHERE transfer_id = v_transfer_id AND leg = 'DEBIT';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Ledger invariant violation: SUCCEEDED transfer % is missing a DEBIT leg',
                v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_debit.account_id <> v_transfer.source_account_id THEN
            RAISE EXCEPTION 'Ledger invariant violation: DEBIT account % does not match transfer source account % for transfer %',
                v_debit.account_id, v_transfer.source_account_id, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_debit.amount <> v_transfer.amount THEN
            RAISE EXCEPTION 'Ledger invariant violation: DEBIT amount % does not match transfer amount % for transfer %',
                v_debit.amount, v_transfer.amount, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_debit.currency <> v_transfer.currency THEN
            RAISE EXCEPTION 'Ledger invariant violation: DEBIT currency % does not match transfer currency % for transfer %',
                v_debit.currency, v_transfer.currency, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        -- 3. Validate CREDIT entry
        SELECT * INTO v_credit FROM ledger_entries WHERE transfer_id = v_transfer_id AND leg = 'CREDIT';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Ledger invariant violation: SUCCEEDED transfer % is missing a CREDIT leg',
                v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_credit.account_id <> v_transfer.destination_account_id THEN
            RAISE EXCEPTION 'Ledger invariant violation: CREDIT account % does not match transfer destination account % for transfer %',
                v_credit.account_id, v_transfer.destination_account_id, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_credit.amount <> v_transfer.amount THEN
            RAISE EXCEPTION 'Ledger invariant violation: CREDIT amount % does not match transfer amount % for transfer %',
                v_credit.amount, v_transfer.amount, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_credit.currency <> v_transfer.currency THEN
            RAISE EXCEPTION 'Ledger invariant violation: CREDIT currency % does not match transfer currency % for transfer %',
                v_credit.currency, v_transfer.currency, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

    ELSE
        -- Non-SUCCEEDED transfers (PENDING, PROCESSING, FAILED) must have ZERO ledger entries
        SELECT COUNT(*) INTO v_entry_count FROM ledger_entries WHERE transfer_id = v_transfer_id;
        IF v_entry_count > 0 THEN
            RAISE EXCEPTION 'Ledger invariant violation: transfer % with status % must have 0 ledger entries, found %',
                v_transfer_id, v_transfer.status, v_entry_count
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Attach deferred constraint trigger on transfers
CREATE CONSTRAINT TRIGGER trg_transfer_ledger_invariants_transfers
AFTER INSERT OR UPDATE ON transfers
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_transfer_ledger_invariants();

-- Attach deferred constraint trigger on ledger_entries
CREATE CONSTRAINT TRIGGER trg_transfer_ledger_invariants_ledger
AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_transfer_ledger_invariants();
