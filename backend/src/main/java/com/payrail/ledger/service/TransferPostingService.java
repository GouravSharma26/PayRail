package com.payrail.ledger.service;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.common.exception.InvalidTransferException;
import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative atomic transfer posting engine.
 * Guarantees that account balances, double-entry ledger legs, and transfer terminal
 * state commit in a single, atomic PostgreSQL transaction.
 */
@Service
public class TransferPostingService {

    private static final Logger log = LoggerFactory.getLogger(TransferPostingService.class);

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final TransferStatusHistoryRepository statusHistoryRepository;
    private PostingFailureInjector failureInjector = PostingFailureInjector.noop();

    public TransferPostingService(
            TransferRepository transferRepository,
            AccountRepository accountRepository,
            LedgerRepository ledgerRepository,
            TransferStatusHistoryRepository statusHistoryRepository
    ) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.statusHistoryRepository = statusHistoryRepository;
    }

    @Autowired(required = false)
    public void setFailureInjector(PostingFailureInjector failureInjector) {
        if (failureInjector != null) {
            this.failureInjector = failureInjector;
        }
    }

    /**
     * Atomically posts a transfer:
     * 1. Obtains row lock on transfer.
     * 2. Checks terminal state (idempotency guard).
     * 3. Obtains row locks on source and destination accounts in deterministic UUID order (deadlock avoidance).
     * 4. Validates balances, account status, and currency consistency.
     * 5. Debits sender, credits recipient, writes balanced ledger pair, records history, and updates transfer.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferPostingResult postTransfer(UUID transferId) {
        Instant now = Instant.now();

        // 1. Lock transfer row
        TransferRecord transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer with identifier '" + transferId + "' was not found."));

        // 2. Terminal state / idempotency guard
        if (transfer.status() == TransferStatus.SUCCEEDED) {
            log.info("Transfer id={} is already SUCCEEDED; returning existing ledger postings idempotently.", transferId);
            List<LedgerEntryRecord> existingEntries = ledgerRepository.findByTransferId(transferId);
            return TransferPostingResult.alreadyProcessed(transferId, TransferStatus.SUCCEEDED, existingEntries);
        }

        if (transfer.status() == TransferStatus.FAILED) {
            log.warn("Transfer id={} is already FAILED (code={}); cannot reprocess terminal failure.",
                    transferId, transfer.failureCode());
            return TransferPostingResult.alreadyProcessed(transferId, TransferStatus.FAILED, Collections.emptyList());
        }

        UUID srcId = transfer.sourceAccountId();
        UUID dstId = transfer.destinationAccountId();

        if (srcId.equals(dstId)) {
            throw new InvalidTransferException("Source and destination accounts must be different.");
        }

        // 3. Acquire row locks in deterministic ascending UUID order to prevent deadlocks
        UUID firstLockId = srcId.compareTo(dstId) < 0 ? srcId : dstId;
        UUID secondLockId = srcId.compareTo(dstId) < 0 ? dstId : srcId;

        AccountRecord firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Account with identifier '" + firstLockId + "' was not found."));
        AccountRecord secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Account with identifier '" + secondLockId + "' was not found."));

        AccountRecord srcAccount = firstLockId.equals(srcId) ? firstAccount : secondAccount;
        AccountRecord dstAccount = firstLockId.equals(dstId) ? firstAccount : secondAccount;

        // 4. Validate accounts are ACTIVE
        if (srcAccount.status() != AccountStatus.ACTIVE) {
            log.warn("Transfer id={} declined: source account id={} is INACTIVE", transferId, srcId);
            return recordTerminalFailure(transfer, "ACCOUNT_INACTIVE", "Source account is inactive", now);
        }
        if (dstAccount.status() != AccountStatus.ACTIVE) {
            log.warn("Transfer id={} declined: destination account id={} is INACTIVE", transferId, dstId);
            return recordTerminalFailure(transfer, "ACCOUNT_INACTIVE", "Destination account is inactive", now);
        }

        // 5. Validate currencies match
        if (!srcAccount.currency().equals(transfer.currency()) || !dstAccount.currency().equals(transfer.currency())) {
            log.warn("Transfer id={} declined: currency mismatch (transfer={}, src={}, dst={})",
                    transferId, transfer.currency(), srcAccount.currency(), dstAccount.currency());
            return recordTerminalFailure(transfer, "CURRENCY_MISMATCH", "Transfer currency does not match account currencies", now);
        }

        // 6. Check available funds
        if (srcAccount.currentBalance().compareTo(transfer.amount()) < 0) {
            log.warn("Transfer id={} declined: insufficient funds in account id={} (available={}, required={})",
                    transferId, srcId, srcAccount.currentBalance(), transfer.amount());
            return recordTerminalFailure(transfer, "INSUFFICIENT_FUNDS", "Insufficient available balance in source account", now);
        }

        // 7. Test-controlled failure injection point
        failureInjector.beforeLedgerWrite(transfer);

        // 8. Update account balances atomically
        BigDecimal newSrcBalance = srcAccount.currentBalance().subtract(transfer.amount());
        BigDecimal newDstBalance = dstAccount.currentBalance().add(transfer.amount());

        accountRepository.updateBalance(srcId, newSrcBalance, now);
        accountRepository.updateBalance(dstId, newDstBalance, now);

        // 9. Write immutable double-entry ledger pair
        LedgerEntryRecord debit = new LedgerEntryRecord(
                UUID.randomUUID(),
                transfer.id(),
                srcId,
                LedgerLeg.DEBIT,
                transfer.amount(),
                transfer.currency(),
                now
        );

        LedgerEntryRecord credit = new LedgerEntryRecord(
                UUID.randomUUID(),
                transfer.id(),
                dstId,
                LedgerLeg.CREDIT,
                transfer.amount(),
                transfer.currency(),
                now
        );

        ledgerRepository.insertPair(debit, credit);

        // 10. Mark transfer SUCCEEDED
        transferRepository.updateStatus(transfer.id(), TransferStatus.SUCCEEDED, null, null, now, now);

        // 11. Record status transition history
        statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                null,
                transfer.id(),
                transfer.status(),
                TransferStatus.SUCCEEDED,
                "TRANSFER_POSTED",
                transfer.processingAttemptCount() + 1,
                transfer.correlationId(),
                now
        ));

        log.info("Successfully posted transfer id={} amount={} {} (debit={}, credit={})",
                transferId, transfer.amount(), transfer.currency(), srcId, dstId);

        return TransferPostingResult.succeeded(transferId, List.of(debit, credit));
    }

    private TransferPostingResult recordTerminalFailure(TransferRecord transfer, String code, String message, Instant now) {
        transferRepository.updateStatus(transfer.id(), TransferStatus.FAILED, code, message, now, now);

        statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                null,
                transfer.id(),
                transfer.status(),
                TransferStatus.FAILED,
                code,
                transfer.processingAttemptCount() + 1,
                transfer.correlationId(),
                now
        ));

        return TransferPostingResult.failed(transfer.id(), code, message);
    }
}
