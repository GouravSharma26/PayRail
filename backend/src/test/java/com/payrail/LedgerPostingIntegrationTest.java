package com.payrail;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.common.exception.InvalidTransferException;
import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.ledger.service.PostingFailureInjector;
import com.payrail.ledger.service.ReconciliationReport;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.ledger.service.TransferPostingResult;
import com.payrail.ledger.service.TransferPostingService;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferPostingService postingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private TransferStatusHistoryRepository historyRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    @DisplayName("Should successfully post transfer with balanced debit/credit pair and update balances atomically")
    void shouldPostTransferSuccessfully() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "150.00", "INR");

        TransferPostingResult result = postingService.postTransfer(transferId);

        assertThat(result.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(result.isAlreadyProcessed()).isFalse();
        assertThat(result.ledgerEntries()).hasSize(2);

        // Verify account balance projections
        AccountRecord updatedSrc = accountRepository.findById(srcId).orElseThrow();
        AccountRecord updatedDst = accountRepository.findById(dstId).orElseThrow();
        assertThat(updatedSrc.currentBalance()).isEqualByComparingTo("350.00");
        assertThat(updatedDst.currentBalance()).isEqualByComparingTo("250.00");

        // Verify ledger entries
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);
        LedgerEntryRecord debit = entries.stream().filter(e -> e.leg() == LedgerLeg.DEBIT).findFirst().orElseThrow();
        LedgerEntryRecord credit = entries.stream().filter(e -> e.leg() == LedgerLeg.CREDIT).findFirst().orElseThrow();

        assertThat(debit.accountId()).isEqualTo(srcId);
        assertThat(debit.amount()).isEqualByComparingTo("150.00");
        assertThat(debit.currency()).isEqualTo("INR");

        assertThat(credit.accountId()).isEqualTo(dstId);
        assertThat(credit.amount()).isEqualByComparingTo("150.00");
        assertThat(credit.currency()).isEqualTo("INR");

        // Verify transfer record
        TransferRecord updatedTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(updatedTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(updatedTransfer.completedAt()).isNotNull();

        // Verify audit history
        List<TransferStatusHistoryRecord> history = historyRepository.findByTransferId(transferId);
        assertThat(history).anyMatch(h -> h.toStatus() == TransferStatus.SUCCEEDED && "TRANSFER_POSTED".equals(h.reasonCode()));

        // Verify continuous reconciliation audit
        ReconciliationReport report = reconciliationService.runFullAudit();
        assertThat(report.isBalanced()).isTrue();
        assertThat(report.discrepancyDetails()).isEmpty();
    }

    @Test
    @DisplayName("Should decline transfer when sender has insufficient funds, leaving balances untouched and writing no ledger entries")
    void shouldDeclineTransferWithInsufficientFunds() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Broke Sender", "INR", "50.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        TransferPostingResult result = postingService.postTransfer(transferId);

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");

        // Balances must remain unchanged
        AccountRecord updatedSrc = accountRepository.findById(srcId).orElseThrow();
        AccountRecord updatedDst = accountRepository.findById(dstId).orElseThrow();
        assertThat(updatedSrc.currentBalance()).isEqualByComparingTo("50.00");
        assertThat(updatedDst.currentBalance()).isEqualByComparingTo("100.00");

        // Zero ledger entries must be written
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).isEmpty();

        // Transfer status must be FAILED
        TransferRecord updatedTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(updatedTransfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(updatedTransfer.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");

        // Reconciliation remains balanced
        ReconciliationReport report = reconciliationService.runFullAudit();
        assertThat(report.isBalanced()).isTrue();
    }

    @Test
    @DisplayName("Should decline transfer when source account is inactive")
    void shouldDeclineWhenSourceAccountIsInactive() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createInactiveAccount(srcId, "Inactive Account", "INR", "500.00");
        createAccount(dstId, "Active Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        TransferPostingResult result = postingService.postTransfer(transferId);

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("ACCOUNT_INACTIVE");

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Should decline transfer on currency mismatch between accounts and transfer")
    void shouldDeclineOnCurrencyMismatch() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "INR Sender", "INR", "500.00");
        createAccount(dstId, "USD Recipient", "USD", "500.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");

        TransferPostingResult result = postingService.postTransfer(transferId);

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("CURRENCY_MISMATCH");

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Should reject same-account transfers: database constraint chk_transfers_distinct_accounts prohibits same account")
    void shouldRejectSameAccountTransfer() {
        UUID accountId = UUID.randomUUID();
        createAccount(accountId, "Self Account", "INR", "500.00");

        UUID transferId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO transfers (
                    id, source_account_id, destination_account_id, amount, currency,
                    status, correlation_id, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 50.00, 'INR', 'PENDING', ?, now(), now()
                )
                """, transferId, accountId, accountId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when transfer does not exist")
    void shouldThrowWhenTransferNotFound() {
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> postingService.postTransfer(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Should handle duplicate posting attempts idempotently without creating duplicate ledger entries")
    void shouldHandleDuplicatePostingIdempotently() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "200.00", "INR");

        // First posting
        TransferPostingResult firstResult = postingService.postTransfer(transferId);
        assertThat(firstResult.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(firstResult.isAlreadyProcessed()).isFalse();

        // Second duplicate posting
        TransferPostingResult secondResult = postingService.postTransfer(transferId);
        assertThat(secondResult.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(secondResult.isAlreadyProcessed()).isTrue();

        // Third duplicate posting
        TransferPostingResult thirdResult = postingService.postTransfer(transferId);
        assertThat(thirdResult.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(thirdResult.isAlreadyProcessed()).isTrue();

        // Balances must be debited/credited exactly once
        AccountRecord updatedSrc = accountRepository.findById(srcId).orElseThrow();
        AccountRecord updatedDst = accountRepository.findById(dstId).orElseThrow();
        assertThat(updatedSrc.currentBalance()).isEqualByComparingTo("800.00");
        assertThat(updatedDst.currentBalance()).isEqualByComparingTo("400.00");

        // Exactly two ledger entries exist
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);
    }

    @Test
    @DisplayName("Should atomically roll back all balance changes when failure occurs before ledger write")
    void shouldRollbackAtomicallyOnFailureInjection() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "600.00");
        createAccount(dstId, "Recipient", "INR", "150.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        // Inject simulated system/broker failure immediately before ledger write
        postingService.setFailureInjector(t -> {
            throw new RuntimeException("Simulated catastrophic crash before ledger write");
        });

        try {
            assertThatThrownBy(() -> postingService.postTransfer(transferId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated catastrophic crash");

            // Entire transaction must have rolled back
            AccountRecord src = accountRepository.findById(srcId).orElseThrow();
            AccountRecord dst = accountRepository.findById(dstId).orElseThrow();
            assertThat(src.currentBalance()).isEqualByComparingTo("600.00");
            assertThat(dst.currentBalance()).isEqualByComparingTo("150.00");

            List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
            assertThat(entries).isEmpty();

            TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
            assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
        } finally {
            // Reset injector to clean state
            postingService.setFailureInjector(PostingFailureInjector.noop());
        }
    }

    @Test
    @DisplayName("Should enforce ledger immutability: direct SQL UPDATE or DELETE on ledger_entries must fail")
    void shouldEnforceLedgerImmutabilityViaDatabaseTrigger() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");
        postingService.postTransfer(transferId);

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);
        UUID entryId = entries.get(0).id();

        // 1. Attempt UPDATE on ledger entry -> must fail with PostgreSQL trigger exception
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_entries SET amount = 999.00 WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 2. Attempt DELETE on ledger entry -> must fail with PostgreSQL trigger exception
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify entry remains unmodified
        List<LedgerEntryRecord> afterAttempt = ledgerRepository.findByTransferId(transferId);
        assertThat(afterAttempt).hasSize(2);
        assertThat(afterAttempt.get(0).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Should enforce account non-negative constraint: current_balance < 0 must fail at database level")
    void shouldEnforceAccountNonNegativeConstraint() {
        UUID accountId = UUID.randomUUID();
        createAccount(accountId, "Test Negative", "INR", "10.00");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE accounts SET current_balance = -5.00 WHERE id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should enforce unique ledger leg constraint: duplicate DEBIT or CREDIT leg for same transfer must fail")
    void shouldEnforceUniqueTransferLegConstraint() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "500.00");
        createAccount(dstId, "Recipient", "INR", "100.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "50.00", "INR");
        postingService.postTransfer(transferId);

        // Attempt to insert a second DEBIT leg for the same transfer
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                VALUES (?, ?, ?, 'DEBIT', 50.00, 'INR', now())
                """, UUID.randomUUID(), transferId, srcId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Helper setup methods
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

    private void createInactiveAccount(UUID id, String name, String currency, String balance) {
        accountRepository.insert(new AccountRecord(
                id,
                name,
                currency,
                new BigDecimal(balance),
                new BigDecimal(balance),
                AccountStatus.INACTIVE,
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
