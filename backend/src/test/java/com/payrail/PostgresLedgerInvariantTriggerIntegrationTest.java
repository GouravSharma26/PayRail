package com.payrail;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresLedgerInvariantTriggerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    @DisplayName("Deferred trigger rejects committing a SUCCEEDED transfer that lacks exactly two balanced ledger entries")
    void shouldRejectSucceededTransferWithoutLedgerEntriesAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        // Attempt to update transfer to SUCCEEDED with zero ledger entries
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                UPDATE transfers
                SET status = 'SUCCEEDED', completed_at = now()
                WHERE id = '%s';
                """, transferId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Ledger invariant violation")
                .hasMessageContaining("must have exactly 2 ledger entries, found 0");
    }

    @Test
    @DisplayName("Deferred trigger rejects committing ledger entries for a transfer in non-SUCCEEDED status (e.g. PENDING)")
    void shouldRejectLedgerEntriesForPendingTransferAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        // Attempt to insert ledger entry while transfer is still PENDING (with balanced account to isolate transfer trigger)
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE accounts SET current_balance = current_balance - 100.00 WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 100.00, 'INR', now());
                END $$;
                """, srcId, UUID.randomUUID(), transferId, srcId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Ledger invariant violation")
                .hasMessageContaining("with status PENDING must have 0 ledger entries");
    }

    @Test
    @DisplayName("Deferred trigger rejects committing DEBIT leg associated with wrong account (not source_account_id)")
    void shouldRejectDebitWithWrongAccountIdAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        UUID wrongAccountId = UUID.randomUUID();
        createAccount(srcId, "Source Account", "INR", "500.00");
        createAccount(dstId, "Destination Account", "INR", "100.00");
        createAccount(wrongAccountId, "Wrong Account", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        // Multi-statement transaction in single execution to test deferred trigger evaluation
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 50.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 50.00, 'INR', now());
                END $$;
                """,
                transferId,
                UUID.randomUUID(), transferId, wrongAccountId, // WRONG account for DEBIT
                UUID.randomUUID(), transferId, dstId
        )))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("DEBIT account")
                .hasMessageContaining("does not match transfer source account");
    }

    @Test
    @DisplayName("Deferred trigger rejects committing ledger entries with mismatched amount")
    void shouldRejectLedgerEntryWithMismatchedAmountAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Source Account", "INR", "500.00");
        createAccount(dstId, "Destination Account", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 50.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 99.00, 'INR', now()); -- Mismatched amount
                END $$;
                """,
                transferId,
                UUID.randomUUID(), transferId, srcId,
                UUID.randomUUID(), transferId, dstId
        )))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CREDIT amount")
                .hasMessageContaining("does not match transfer amount");
    }

    @Test
    @DisplayName("Deferred trigger rejects committing ledger entries with mismatched currency")
    void shouldRejectLedgerEntryWithMismatchedCurrencyAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Source Account", "INR", "500.00");
        createAccount(dstId, "Destination Account", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 50.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 50.00, 'USD', now()); -- Mismatched currency
                END $$;
                """,
                transferId,
                UUID.randomUUID(), transferId, srcId,
                UUID.randomUUID(), transferId, dstId
        )))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CREDIT currency")
                .hasMessageContaining("does not match transfer currency");
    }

    @Test
    @DisplayName("Deferred trigger permits valid atomic insertion of transfer and balanced ledger entries within same transaction")
    void shouldPermitValidPostingWithinSingleTransaction() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Source Account", "INR", "500.00");
        createAccount(dstId, "Destination Account", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "75.00", "INR");

        // Multi-statement transaction in single execution with valid debit/credit and consistent balance updates
        jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE accounts SET current_balance = current_balance - 75.00, updated_at = now() WHERE id = '%s';
                    UPDATE accounts SET current_balance = current_balance + 75.00, updated_at = now() WHERE id = '%s';
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 75.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 75.00, 'INR', now());
                END $$;
                """,
                srcId,
                dstId,
                transferId,
                UUID.randomUUID(), transferId, srcId,
                UUID.randomUUID(), transferId, dstId
        ));

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?",
                Integer.class,
                transferId
        );
        assertThat(entryCount).isEqualTo(2);

        AccountRecord updatedSrc = accountRepository.findById(srcId).orElseThrow();
        AccountRecord updatedDst = accountRepository.findById(dstId).orElseThrow();
        assertThat(updatedSrc.currentBalance()).isEqualByComparingTo("425.00");
        assertThat(updatedDst.currentBalance()).isEqualByComparingTo("175.00");
    }

    @Test
    @DisplayName("Finding 2: Database deferred trigger rejects committing a transfer whose currency does not match source or destination account currency")
    void shouldRejectTransferWithCurrencyMismatchAgainstAccountAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender INR", "INR", "500.00");
        createAccount(dstId, "Recipient INR", "INR", "100.00");

        UUID transferId = UUID.randomUUID();

        // Attempt direct SQL insertion of transfer with EUR currency between INR accounts
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO transfers (
                    id, source_account_id, destination_account_id, amount, currency,
                    status, correlation_id, created_at, updated_at
                ) VALUES (
                    '%s', '%s', '%s', 50.00, 'EUR', 'PENDING', '%s', now(), now()
                );
                """, transferId, srcId, dstId, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("transfer currency EUR does not match source account currency INR");
    }

    @Test
    @DisplayName("Finding 2: Database deferred trigger rejects committing a successful transfer with valid debit/credit pair but unchanged account balances")
    void shouldRejectSucceededTransferWithUnchangedAccountBalancesAtCommit() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        // Legitimate ledger entries and SUCCEEDED transfer status, but account balances are NOT updated
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 50.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 50.00, 'INR', now());
                END $$;
                """,
                transferId,
                UUID.randomUUID(), transferId, srcId,
                UUID.randomUUID(), transferId, dstId
        )))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Account balance invariant violation")
                .hasMessageContaining("current_balance (500.00) does not match opening_balance (500.00) + credits (0.00) - debits (50.00) = expected 450.00");
    }

    @Test
    @DisplayName("Finding 2: Database deferred trigger rejects committing an account balance changed independently of ledger activity")
    void shouldRejectIndependentAccountBalanceChangeWithoutLedgerActivity() {
        UUID accountId = UUID.randomUUID();
        createAccount(accountId, "Tampered Account", "INR", "1000.00");

        // Attempt direct SQL balance update without corresponding ledger entries
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                UPDATE accounts SET current_balance = 1250.00 WHERE id = '%s';
                """, accountId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Account balance invariant violation")
                .hasMessageContaining("current_balance (1250.00) does not match opening_balance (1000.00) + credits (0.00) - debits (0.00) = expected 1000.00");
    }

    @Test
    @DisplayName("Finding 5: Immutability triggers throw check_violation (23514) and are classified as DataIntegrityViolationException, NOT DuplicateKeyException")
    void shouldClassifyImmutabilityViolationAsDataIntegrityViolationNotDuplicateKey() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        UUID entryId = UUID.randomUUID();
        jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE accounts SET current_balance = current_balance - 50.00, updated_at = now() WHERE id = '%s';
                    UPDATE accounts SET current_balance = current_balance + 50.00, updated_at = now() WHERE id = '%s';
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 50.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 50.00, 'INR', now());
                END $$;
                """,
                srcId,
                dstId,
                transferId,
                entryId, transferId, srcId,
                UUID.randomUUID(), transferId, dstId
        ));

        // Attempting to UPDATE or DELETE an existing ledger entry must throw DataIntegrityViolationException
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_entries SET amount = 999.00 WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("Ledger entries are strictly append-only and immutable");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("Ledger entries are strictly append-only and immutable");
    }

    @Test
    @DisplayName("Finding 1: Database trigger rejects changing account currency before any transfer exists")
    void shouldRejectAccountCurrencyUpdateBeforeAnyTransferExists() {
        UUID accountId = UUID.randomUUID();
        createAccount(accountId, "Standalone Account", "INR", "500.00");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE accounts SET currency = 'USD' WHERE id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Account invariant violation")
                .hasMessageContaining("currency is immutable (attempted modification from INR to USD)");

        AccountRecord account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("Finding 1: Database trigger rejects changing currency of account referenced by transfer and ledger, keeping state intact")
    void shouldRejectAccountCurrencyUpdateWhenReferencedBySuccessfulTransferAndLedger() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "150.00", "INR");

        // Execute successful posting with balanced ledger entries
        UUID debitEntryId = UUID.randomUUID();
        UUID creditEntryId = UUID.randomUUID();
        jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE accounts SET current_balance = current_balance - 150.00, updated_at = now() WHERE id = '%s';
                    UPDATE accounts SET current_balance = current_balance + 150.00, updated_at = now() WHERE id = '%s';
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 150.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 150.00, 'INR', now());
                END $$;
                """,
                srcId, dstId, transferId,
                debitEntryId, transferId, srcId,
                creditEntryId, transferId, dstId
        ));

        // Attempt direct SQL currency change on source account
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE accounts SET currency = 'EUR' WHERE id = ?", srcId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Account invariant violation")
                .hasMessageContaining("currency is immutable (attempted modification from INR to EUR)");

        // Verify account currency, balance, and ledger entries remain completely unchanged
        AccountRecord src = accountRepository.findById(srcId).orElseThrow();
        assertThat(src.currency()).isEqualTo("INR");
        assertThat(src.currentBalance()).isEqualByComparingTo("850.00");

        String debitCurrency = jdbcTemplate.queryForObject(
                "SELECT currency FROM ledger_entries WHERE id = ?",
                String.class,
                debitEntryId
        );
        assertThat(debitCurrency).isEqualTo("INR");
    }

    @Test
    @DisplayName("Finding 2: Database trigger rejects modifying only opening_balance on accounts")
    void shouldRejectAccountOpeningBalanceUpdateOnly() {
        UUID accountId = UUID.randomUUID();
        createAccount(accountId, "Baseline Account", "INR", "1000.00");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE accounts SET opening_balance = 2000.00 WHERE id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Account invariant violation")
                .hasMessageContaining("opening_balance is immutable (attempted modification from 1000.00 to 2000.00)");

        AccountRecord account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.openingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Finding 2: Database trigger rejects coupled update of opening_balance and current_balance by same amount")
    void shouldRejectCoupledOpeningBalanceAndCurrentBalanceUpdate() {
        UUID accountId = UUID.randomUUID();
        createAccount(accountId, "Coupled Tampering Account", "INR", "1000.00");

        // Attempting to change opening_balance and current_balance together to fool projection trigger
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE accounts SET opening_balance = 1500.00, current_balance = 1500.00 WHERE id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Account invariant violation")
                .hasMessageContaining("opening_balance is immutable (attempted modification from 1000.00 to 1500.00)");

        AccountRecord account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(account.currentBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Finding 1 & 2: Valid transfer modifies current_balance while opening_balance and currency remain unchanged")
    void shouldPermitValidTransferModifyingCurrentBalanceWhileOpeningBalanceRemainsUnchanged() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "500.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "200.00", "INR");

        jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE accounts SET current_balance = current_balance - 200.00, updated_at = now() WHERE id = '%s';
                    UPDATE accounts SET current_balance = current_balance + 200.00, updated_at = now() WHERE id = '%s';
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 200.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 200.00, 'INR', now());
                END $$;
                """,
                srcId, dstId, transferId,
                UUID.randomUUID(), transferId, srcId,
                UUID.randomUUID(), transferId, dstId
        ));

        AccountRecord src = accountRepository.findById(srcId).orElseThrow();
        AccountRecord dst = accountRepository.findById(dstId).orElseThrow();

        assertThat(src.currentBalance()).isEqualByComparingTo("800.00");
        assertThat(src.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(src.currency()).isEqualTo("INR");

        assertThat(dst.currentBalance()).isEqualByComparingTo("700.00");
        assertThat(dst.openingBalance()).isEqualByComparingTo("500.00");
        assertThat(dst.currency()).isEqualTo("INR");
    }

    private void createAccount(UUID id, String name, String currency, String balance) {
        accountRepository.insert(new AccountRecord(
                id,
                name,
                currency,
                new BigDecimal(balance),
                new BigDecimal(balance),
                AccountStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
    }

    private void createPendingTransfer(UUID id, UUID src, UUID dst, String amount, String currency) {
        transferRepository.insert(new TransferRecord(
                id,
                src,
                dst,
                new BigDecimal(amount),
                currency,
                TransferStatus.PENDING,
                null,
                null,
                UUID.randomUUID(),
                0,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null
        ));
    }
}
