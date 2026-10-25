package com.payrail.ledger.service;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.repository.AccountRepository;
import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import com.payrail.reconciliation.service.ReconciliationAuditExecutor;
import com.payrail.reconciliation.service.ReconciliationCoordinator;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Continuous audit and reconciliation service facade.
 * Maintains balance and transfer consistency verification and delegates audit coordination
 * and transactional persistence to {@link ReconciliationCoordinator} and {@link ReconciliationAuditExecutor}.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerRepository ledgerRepository;
    private final ReconciliationCoordinator coordinator;
    private final ReconciliationAuditExecutor auditExecutor;

    public ReconciliationService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerRepository ledgerRepository,
            ReconciliationCoordinator coordinator,
            ReconciliationAuditExecutor auditExecutor
    ) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
        this.coordinator = coordinator;
        this.auditExecutor = auditExecutor;
    }

    @Autowired(required = false)
    public void setMetrics(com.payrail.config.PayRailMetrics metrics) {
        this.auditExecutor.setMetrics(metrics);
    }

    @Autowired(required = false)
    public void setPayRailProperties(com.payrail.config.PayRailProperties payRailProperties) {
        // Properties injected directly into coordinator
    }

    public void setClock(Clock clock) {
        this.coordinator.setClock(clock);
        this.auditExecutor.setClock(clock);
    }

    public void setLastAuditFinishedAt(Instant instant) {
        this.coordinator.setLastAuditFinishedAt(instant);
    }

    /**
     * Verifies that an individual account's stored current balance matches its
     * opening balance plus net ledger movements.
     */
    @Transactional(readOnly = true)
    public boolean verifyAccountBalanceConsistency(UUID accountId) {
        AccountRecord account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account with identifier '" + accountId + "' was not found."));

        BigDecimal netMovement = ledgerRepository.calculateNetMovementForAccount(accountId);
        BigDecimal expectedBalance = account.openingBalance().add(netMovement);

        boolean matches = expectedBalance.compareTo(account.currentBalance()) == 0;
        if (!matches) {
            log.error("AUDIT MISMATCH on account id={}: opening={}, netMovement={}, expected={}, actual={}",
                    accountId, account.openingBalance(), netMovement, expectedBalance, account.currentBalance());
        }
        return matches;
    }

    /**
     * Verifies that a transfer has balanced double-entry ledger legs.
     */
    @Transactional(readOnly = true)
    public boolean verifyTransferLedgerBalance(UUID transferId) {
        TransferRecord transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer with identifier '" + transferId + "' was not found."));

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);

        if (transfer.status() != TransferStatus.SUCCEEDED) {
            return entries.isEmpty();
        }

        if (entries.size() != 2) {
            return false;
        }

        LedgerEntryRecord debit = entries.stream().filter(e -> e.leg() == LedgerLeg.DEBIT).findFirst().orElse(null);
        LedgerEntryRecord credit = entries.stream().filter(e -> e.leg() == LedgerLeg.CREDIT).findFirst().orElse(null);

        if (debit == null || credit == null) {
            return false;
        }

        return debit.amount().compareTo(transfer.amount()) == 0
                && credit.amount().compareTo(transfer.amount()) == 0
                && debit.currency().equals(transfer.currency())
                && credit.currency().equals(transfer.currency())
                && debit.accountId().equals(transfer.sourceAccountId())
                && credit.accountId().equals(transfer.destinationAccountId());
    }

    /**
     * Runs full database-wide audit across all accounts, transfers, and ledger entries.
     */
    public ReconciliationReport runFullAudit() {
        return auditExecutor.runFullAudit();
    }

    /**
     * Public manual trigger endpoint handler.
     * Delegates to {@link ReconciliationCoordinator#triggerManualAudit()}.
     */
    public ReconciliationRunRecord triggerManualAudit() {
        return coordinator.triggerManualAudit();
    }

    /**
     * Executes full audit and durably records reconciliation_runs and reconciliation_issues.
     * Routes through {@link ReconciliationCoordinator#runAudit(String)} to ensure single-flight lock
     * and proxy-based transaction boundaries.
     */
    public ReconciliationRunRecord runAuditAndPersist(String triggerType) {
        return coordinator.runAudit(triggerType);
    }

    /**
     * Scheduled audit trigger.
     * Delegates to {@link ReconciliationCoordinator#triggerScheduledAudit()}.
     */
    public void scheduledAudit() {
        coordinator.scheduledAudit();
    }
}
