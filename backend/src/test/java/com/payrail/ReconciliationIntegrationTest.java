package com.payrail;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.ledger.service.ReconciliationReport;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.ledger.service.TransferPostingService;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferPostingService postingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    @DisplayName("Finding 3: Audit reports completely balanced when accounts and ledger entries match")
    void shouldReportBalancedWhenClean() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "150.00", "INR");
        postingService.postTransfer(transferId);

        ReconciliationReport report = reconciliationService.runFullAudit();

        assertThat(report.isBalanced()).isTrue();
        assertThat(report.inconsistentAccountBalancesCount()).isZero();
        assertThat(report.unbalancedTransfersCount()).isZero();
        assertThat(report.discrepancyDetails()).isEmpty();
    }

    @Test
    @DisplayName("Finding 3: Audit detects account balance discrepancy (stored balance != opening + net ledger movement)")
    void shouldDetectAccountBalanceDiscrepancy() {
        UUID srcId = UUID.randomUUID();
        createAccount(srcId, "Corrupted Account", "INR", "1000.00");

        // Corrupt current_balance directly in database without ledger movement
        withTriggersDisabled(() -> {
            jdbcTemplate.update("UPDATE accounts SET current_balance = 1250.00 WHERE id = ?", srcId);
        });

        ReconciliationReport report = reconciliationService.runFullAudit();

        assertThat(report.isBalanced()).isFalse();
        assertThat(report.inconsistentAccountBalancesCount()).isEqualTo(1);
        assertThat(report.discrepancyDetails())
                .anyMatch(d -> d.contains("balance mismatch") && d.contains(srcId.toString()));
    }

    @Test
    @DisplayName("Finding 3: Audit detects DEBIT account mapping anomaly where leg points to wrong account")
    void shouldDetectDebitAccountMismatch() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        UUID wrongAccountId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");
        createAccount(wrongAccountId, "Wrong", "INR", "0.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        // Temporarily bypass commit trigger to inject deliberate anomaly
        withTriggersDisabled(() -> {
            jdbcTemplate.update("UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", transferId);
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'DEBIT', 100.00, 'INR', now())
                    """, UUID.randomUUID(), transferId, wrongAccountId); // WRONG account
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'CREDIT', 100.00, 'INR', now())
                    """, UUID.randomUUID(), transferId, dstId);
        });

        ReconciliationReport report = reconciliationService.runFullAudit();

        assertThat(report.isBalanced()).isFalse();
        assertThat(report.discrepancyDetails())
                .anyMatch(d -> d.contains("DEBIT account mismatch") && d.contains(wrongAccountId.toString()));
    }

    @Test
    @DisplayName("Finding 3: Audit detects CREDIT account mapping anomaly where leg points to wrong account")
    void shouldDetectCreditAccountMismatch() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        UUID wrongAccountId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");
        createAccount(wrongAccountId, "Wrong", "INR", "0.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        withTriggersDisabled(() -> {
            jdbcTemplate.update("UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", transferId);
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'DEBIT', 100.00, 'INR', now())
                    """, UUID.randomUUID(), transferId, srcId);
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'CREDIT', 100.00, 'INR', now())
                    """, UUID.randomUUID(), transferId, wrongAccountId); // WRONG account
        });

        ReconciliationReport report = reconciliationService.runFullAudit();

        assertThat(report.isBalanced()).isFalse();
        assertThat(report.discrepancyDetails())
                .anyMatch(d -> d.contains("CREDIT account mismatch") && d.contains(wrongAccountId.toString()));
    }

    @Test
    @DisplayName("Finding 3: Audit detects amount mismatch between ledger entries and transfer amount")
    void shouldDetectAmountMismatch() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        withTriggersDisabled(() -> {
            jdbcTemplate.update("UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", transferId);
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'DEBIT', 50.00, 'INR', now())
                    """, UUID.randomUUID(), transferId, srcId); // Mismatched 50.00 vs 100.00
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'CREDIT', 50.00, 'INR', now())
                    """, UUID.randomUUID(), transferId, dstId);
        });

        ReconciliationReport report = reconciliationService.runFullAudit();

        assertThat(report.isBalanced()).isFalse();
        assertThat(report.discrepancyDetails())
                .anyMatch(d -> d.contains("amount mismatch") && d.contains("50.00"));
    }

    @Test
    @DisplayName("Finding 3: Audit detects currency mismatch between ledger entries and transfer currency")
    void shouldDetectCurrencyMismatch() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "Sender", "INR", "1000.00");
        createAccount(dstId, "Recipient", "INR", "200.00");

        UUID transferId = UUID.randomUUID();
        createPendingTransfer(transferId, srcId, dstId, "100.00", "INR");

        withTriggersDisabled(() -> {
            jdbcTemplate.update("UPDATE transfers SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", transferId);
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'DEBIT', 100.00, 'USD', now())
                    """, UUID.randomUUID(), transferId, srcId); // USD vs INR
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                    VALUES (?, ?, ?, 'CREDIT', 100.00, 'USD', now())
                    """, UUID.randomUUID(), transferId, dstId);
        });

        ReconciliationReport report = reconciliationService.runFullAudit();

        assertThat(report.isBalanced()).isFalse();
        assertThat(report.discrepancyDetails())
                .anyMatch(d -> d.contains("currency mismatch"));
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
