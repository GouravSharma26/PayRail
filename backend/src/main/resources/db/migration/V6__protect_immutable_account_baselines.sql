-- ============================================================================
-- PayRail Schema Migration V6: Protect Immutable Account Baselines
-- Enforces immutability of account currency and opening_balance
-- ============================================================================

CREATE OR REPLACE FUNCTION prevent_account_baseline_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.currency <> OLD.currency THEN
        RAISE EXCEPTION 'Account invariant violation: account % currency is immutable (attempted modification from % to %)',
            OLD.id, OLD.currency, NEW.currency
            USING ERRCODE = '23514';
    END IF;

    IF NEW.opening_balance <> OLD.opening_balance THEN
        RAISE EXCEPTION 'Account invariant violation: account % opening_balance is immutable (attempted modification from % to %)',
            OLD.id, OLD.opening_balance, NEW.opening_balance
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_account_baseline_mutation
BEFORE UPDATE ON accounts
FOR EACH ROW
EXECUTE FUNCTION prevent_account_baseline_mutation();
