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

        // Attempt to insert ledger entry while transfer is still PENDING
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                VALUES ('%s', '%s', '%s', 'DEBIT', 100.00, 'INR', now());
                """, UUID.randomUUID(), transferId, srcId)))
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

        // Multi-statement transaction in single execution with valid debit/credit
        jdbcTemplate.execute(String.format("""
                DO $$
                BEGIN
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 75.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 75.00, 'INR', now());
                END $$;
                """,
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
                    UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = '%s';
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'DEBIT', 50.00, 'INR', now());
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES ('%s', '%s', '%s', 'CREDIT', 50.00, 'INR', now());
                END $$;
                """,
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
