-- ============================================================================
-- PayRail Schema Migration V5: Account Balance Projection and Currency Invariants
-- Enforces commit-time accounting invariants and account balance projections
-- ============================================================================

-- 1. Upgrade check_transfer_ledger_invariants() to enforce transfer currency matches account currencies
CREATE OR REPLACE FUNCTION check_transfer_ledger_invariants()
RETURNS TRIGGER AS $$
DECLARE
    v_transfer_id UUID;
    v_transfer RECORD;
    v_entry_count INTEGER;
    v_debit RECORD;
    v_credit RECORD;
    v_src_currency VARCHAR(3);
    v_dst_currency VARCHAR(3);
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

    -- Enforce that transfer currency matches both source and destination account currencies at commit time
    IF v_transfer.status <> 'FAILED' THEN
        SELECT currency INTO v_src_currency FROM accounts WHERE id = v_transfer.source_account_id;
        SELECT currency INTO v_dst_currency FROM accounts WHERE id = v_transfer.destination_account_id;

        IF v_src_currency IS NOT NULL AND v_src_currency <> v_transfer.currency THEN
            RAISE EXCEPTION 'Transfer invariant violation: transfer currency % does not match source account currency % for transfer %',
                v_transfer.currency, v_src_currency, v_transfer_id
                USING ERRCODE = '23514';
        END IF;

        IF v_dst_currency IS NOT NULL AND v_dst_currency <> v_transfer.currency THEN
            RAISE EXCEPTION 'Transfer invariant violation: transfer currency % does not match destination account currency % for transfer %',
                v_transfer.currency, v_dst_currency, v_transfer_id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    -- Check invariants based on transfer status
    IF v_transfer.status = 'SUCCEEDED' THEN
        -- Exactly 2 entries must exist
        SELECT COUNT(*) INTO v_entry_count FROM ledger_entries WHERE transfer_id = v_transfer_id;
        IF v_entry_count <> 2 THEN
            RAISE EXCEPTION 'Ledger invariant violation: SUCCEEDED transfer % must have exactly 2 ledger entries, found %',
                v_transfer_id, v_entry_count
                USING ERRCODE = '23514';
        END IF;

        -- Validate DEBIT entry
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

        -- Validate CREDIT entry
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

-- 2. Define check_account_balance_projection_invariants()
-- Enforces: current_balance = opening_balance + sum(CREDIT) - sum(DEBIT)
CREATE OR REPLACE FUNCTION check_account_balance_projection_invariants()
RETURNS TRIGGER AS $$
DECLARE
    v_account_id UUID;
    v_account RECORD;
    v_total_credits NUMERIC(19,2);
    v_total_debits NUMERIC(19,2);
    v_expected_balance NUMERIC(19,2);
BEGIN
    IF TG_TABLE_NAME = 'accounts' THEN
        v_account_id := NEW.id;
    ELSIF TG_TABLE_NAME = 'ledger_entries' THEN
        v_account_id := NEW.account_id;
    END IF;

    -- Fetch final state of account at transaction commit
    SELECT * INTO v_account FROM accounts WHERE id = v_account_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    -- Calculate total credits and debits from committed ledger entries
    SELECT COALESCE(SUM(amount), 0.00) INTO v_total_credits
    FROM ledger_entries
    WHERE account_id = v_account_id AND leg = 'CREDIT';

    SELECT COALESCE(SUM(amount), 0.00) INTO v_total_debits
    FROM ledger_entries
    WHERE account_id = v_account_id AND leg = 'DEBIT';

    v_expected_balance := v_account.opening_balance + v_total_credits - v_total_debits;

    IF v_account.current_balance <> v_expected_balance THEN
        RAISE EXCEPTION 'Account balance invariant violation: account % current_balance (%) does not match opening_balance (%) + credits (%) - debits (%) = expected %',
            v_account_id, v_account.current_balance, v_account.opening_balance, v_total_credits, v_total_debits, v_expected_balance
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Attach deferred constraint triggers for account balance projections
CREATE CONSTRAINT TRIGGER trg_account_balance_invariants_accounts
AFTER INSERT OR UPDATE ON accounts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_account_balance_projection_invariants();

CREATE CONSTRAINT TRIGGER trg_account_balance_invariants_ledger
AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_account_balance_projection_invariants();
