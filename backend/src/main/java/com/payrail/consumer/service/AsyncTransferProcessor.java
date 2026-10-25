package com.payrail.consumer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.consumer.domain.ProcessedMessageRecord;
import com.payrail.consumer.repository.ProcessedMessagesRepository;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.ProcessingAttemptRecord;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.repository.ProcessingAttemptRepository;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
public class AsyncTransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncTransferProcessor.class);
    private static final String CONSUMER_NAME = "transfer-worker";

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final TransferStatusHistoryRepository statusHistoryRepository;
    private final ProcessingAttemptRepository processingAttemptRepository;
    private final ProcessedMessagesRepository processedMessagesRepository;
    private final OutboxRepository outboxRepository;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Value("${payrail.processing.lease-duration:30s}")
    private Duration leaseDuration;

    @Value("${payrail.processing.max-attempts:5}")
    private int maxAttempts;

    @Value("${payrail.processing.initial-backoff:1s}")
    private Duration initialBackoff;

    @Value("${payrail.processing.max-backoff:60s}")
    private Duration maxBackoff;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    private AsyncProcessingFailureInjector failureInjector = AsyncProcessingFailureInjector.noop();

    public AsyncTransferProcessor(
            TransferRepository transferRepository,
            AccountRepository accountRepository,
            LedgerRepository ledgerRepository,
            TransferStatusHistoryRepository statusHistoryRepository,
            ProcessingAttemptRepository processingAttemptRepository,
            ProcessedMessagesRepository processedMessagesRepository,
            OutboxRepository outboxRepository,
            DeadLetterPublisher deadLetterPublisher,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.processingAttemptRepository = processingAttemptRepository;
        this.processedMessagesRepository = processedMessagesRepository;
        this.outboxRepository = outboxRepository;
        this.deadLetterPublisher = deadLetterPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    private com.payrail.config.PayRailMetrics metrics;

    @Autowired(required = false)
    public void setMetrics(com.payrail.config.PayRailMetrics metrics) {
        this.metrics = metrics;
    }

    @Autowired(required = false)
    public void setFailureInjector(AsyncProcessingFailureInjector failureInjector) {
        this.failureInjector = failureInjector != null
                ? failureInjector
                : AsyncProcessingFailureInjector.noop();
    }

    public enum ClaimStatus {
        CLAIMED,
        ALREADY_TERMINAL,
        LEASE_ACTIVE,
        NOT_FOUND
    }

    public record ClaimResult(
            ClaimStatus status,
            TransferRecord transfer,
            UUID claimToken,
            int attemptNumber
    ) {}

    /**
     * Executes the full asynchronous processing lifecycle for a received event envelope.
     */
    public void processTransferEvent(EventEnvelope envelope) {
        UUID eventId = envelope.eventId();

        // 1. Consumer Deduplication Check
        if (processedMessagesRepository.exists(CONSUMER_NAME, eventId)) {
            log.info("Duplicate event id={} already processed by consumer={}; skipping.", eventId, CONSUMER_NAME);
            if (metrics != null) {
                metrics.recordConsumerEventDeduplicated();
            }
            return;
        }

        UUID transferId;
        try {
            transferId = UUID.fromString(envelope.payload().get("transferId").asText());
        } catch (Exception e) {
            log.error("Event id={} missing valid transferId in payload: {}", eventId, envelope.payload());
            transactionTemplate.executeWithoutResult(status -> {
                deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                        transferCommandsTopic,
                        eventId,
                        null,
                        "MALFORMED_PAYLOAD",
                        "Event payload missing valid transferId: " + e.getMessage(),
                        0,
                        Instant.now(),
                        envelope.payload().toString()
                ));
                processedMessagesRepository.insert(new ProcessedMessageRecord(CONSUMER_NAME, eventId, Instant.now(), "MALFORMED_PAYLOAD"));
                if (metrics != null) {
                    metrics.recordConsumerEventDlq();
                }
            });
            return;
        }

        // 2. Claim step (short transaction)
        UUID claimToken = UUID.randomUUID();
        failureInjector.beforeClaim(transferId);
        ClaimResult claim = transactionTemplate.execute(status -> claimTransfer(transferId, claimToken, envelope));

        if (claim == null || claim.status() == ClaimStatus.NOT_FOUND) {
            log.error("Transfer id={} not found for event id={}", transferId, eventId);
            transactionTemplate.executeWithoutResult(status -> {
                deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                        transferCommandsTopic,
                        eventId,
                        transferId,
                        "TRANSFER_NOT_FOUND",
                        "Transfer record does not exist",
                        0,
                        Instant.now(),
                        envelope.payload().toString()
                ));
                processedMessagesRepository.insert(new ProcessedMessageRecord(CONSUMER_NAME, eventId, Instant.now(), "TRANSFER_NOT_FOUND"));
            });
            return;
        }

        if (claim.status() == ClaimStatus.ALREADY_TERMINAL) {
            log.info("Transfer id={} is already in terminal state {}; marking event id={} processed.",
                    transferId, claim.transfer().status(), eventId);
            transactionTemplate.executeWithoutResult(status ->
                    processedMessagesRepository.insert(new ProcessedMessageRecord(CONSUMER_NAME, eventId, Instant.now(), "ALREADY_TERMINAL"))
            );
            return;
        }

        if (claim.status() == ClaimStatus.LEASE_ACTIVE) {
            log.info("Transfer id={} has an active unexpired lease held by another worker; deferring processing.", transferId);
            return;
        }

        // 3. Execution step
        Instant startTime = Instant.now();
        io.micrometer.core.instrument.Timer.Sample sample = metrics != null ? metrics.startProcessingTimer() : null;
        try {
            transactionTemplate.executeWithoutResult(status ->
                    executePosting(transferId, claimToken, claim.attemptNumber(), envelope, startTime)
            );
        } catch (Exception e) {
            log.error("Technical failure processing transfer id={} on attempt {}: {}",
                    transferId, claim.attemptNumber(), e.getMessage());

            // 4. Recovery step on technical failure - fenced by claimToken inside transaction
            transactionTemplate.executeWithoutResult(status -> {
                failureInjector.inRetryStateTransaction(transferId, claim.attemptNumber());
                handleTechnicalFailure(transferId, claimToken, claim.attemptNumber(), envelope, e, startTime);
            });
        } finally {
            if (metrics != null && sample != null) {
                metrics.stopProcessingTimer(sample);
            }
        }
    }

    private ClaimResult claimTransfer(UUID transferId, UUID claimToken, EventEnvelope envelope) {
        Instant now = Instant.now();
        Optional<TransferRecord> transferOpt = transferRepository.findByIdForUpdate(transferId);
        if (transferOpt.isEmpty()) {
            return new ClaimResult(ClaimStatus.NOT_FOUND, null, null, 0);
        }

        TransferRecord transfer = transferOpt.get();

        // Terminal state guard
        if (transfer.status() == TransferStatus.SUCCEEDED || transfer.status() == TransferStatus.FAILED) {
            return new ClaimResult(ClaimStatus.ALREADY_TERMINAL, transfer, null, 0);
        }

        // Active lease guard
        if (transfer.status() == TransferStatus.PROCESSING
                && transfer.processingLeaseUntil() != null
                && transfer.processingLeaseUntil().isAfter(now)) {
            return new ClaimResult(ClaimStatus.LEASE_ACTIVE, transfer, null, 0);
        }

        int newAttemptCount = transfer.processingAttemptCount() + 1;
        Instant leaseUntil = now.plus(leaseDuration);

        // Update transfer to PROCESSING
        transferRepository.claimForProcessing(transferId, claimToken, leaseUntil, now);

        // Record initial attempt IN_PROGRESS
        processingAttemptRepository.insert(new ProcessingAttemptRecord(
                UUID.randomUUID(),
                transferId,
                newAttemptCount,
                claimToken,
                "IN_PROGRESS",
                null,
                null,
                now,
                null,
                null
        ));

        // Record status history transition
        statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                null,
                transferId,
                transfer.status(),
                TransferStatus.PROCESSING,
                "CLAIMED_FOR_PROCESSING",
                newAttemptCount,
                envelope.correlationId(),
                now
        ));

        return new ClaimResult(ClaimStatus.CLAIMED, transfer, claimToken, newAttemptCount);
    }

    private void executePosting(
            UUID transferId,
            UUID claimToken,
            int attemptNumber,
            EventEnvelope envelope,
            Instant startTime
    ) {
        Instant now = Instant.now();

        // Lock transfer row and verify claim token
        TransferRecord transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer disappeared: " + transferId));

        if (!claimToken.equals(transfer.processingClaimToken())) {
            throw new IllegalStateException("Claim token mismatch on transfer id=" + transferId);
        }

        UUID srcId = transfer.sourceAccountId();
        UUID dstId = transfer.destinationAccountId();

        // Lock accounts in deterministic UUID order to prevent deadlocks
        UUID firstLockId = srcId.compareTo(dstId) < 0 ? srcId : dstId;
        UUID secondLockId = srcId.compareTo(dstId) < 0 ? dstId : srcId;

        AccountRecord firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + firstLockId));
        AccountRecord secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + secondLockId));

        AccountRecord srcAccount = firstLockId.equals(srcId) ? firstAccount : secondAccount;
        AccountRecord dstAccount = firstLockId.equals(dstId) ? firstAccount : secondAccount;

        // Validation: Account status
        if (srcAccount.status() != AccountStatus.ACTIVE) {
            handleBusinessDecline(transfer, claimToken, attemptNumber, envelope, "ACCOUNT_INACTIVE", "Source account is inactive", startTime, now);
            return;
        }
        if (dstAccount.status() != AccountStatus.ACTIVE) {
            handleBusinessDecline(transfer, claimToken, attemptNumber, envelope, "ACCOUNT_INACTIVE", "Destination account is inactive", startTime, now);
            return;
        }

        // Validation: Currency match
        if (!srcAccount.currency().equals(transfer.currency()) || !dstAccount.currency().equals(transfer.currency())) {
            handleBusinessDecline(transfer, claimToken, attemptNumber, envelope, "CURRENCY_MISMATCH", "Transfer currency mismatch", startTime, now);
            return;
        }

        // Validation: Available balance
        if (srcAccount.currentBalance().compareTo(transfer.amount()) < 0) {
            handleBusinessDecline(transfer, claimToken, attemptNumber, envelope, "INSUFFICIENT_FUNDS", "Insufficient available balance", startTime, now);
            return;
        }

        // Test-controlled failure injection point
        failureInjector.beforePostingCommit(transfer, attemptNumber);

        // Update account balances
        BigDecimal newSrcBalance = srcAccount.currentBalance().subtract(transfer.amount());
        BigDecimal newDstBalance = dstAccount.currentBalance().add(transfer.amount());

        accountRepository.updateBalance(srcId, newSrcBalance, now);
        accountRepository.updateBalance(dstId, newDstBalance, now);

        // Insert double-entry ledger entries
        LedgerEntryRecord debit = new LedgerEntryRecord(
                UUID.randomUUID(),
                transferId,
                srcId,
                LedgerLeg.DEBIT,
                transfer.amount(),
                transfer.currency(),
                now
        );
        LedgerEntryRecord credit = new LedgerEntryRecord(
                UUID.randomUUID(),
                transferId,
                dstId,
                LedgerLeg.CREDIT,
                transfer.amount(),
                transfer.currency(),
                now
        );
        ledgerRepository.insertPair(debit, credit);

        // Mark transfer SUCCEEDED and clear lease
        transferRepository.completeProcessing(transferId, TransferStatus.SUCCEEDED, null, null, now, now);
        if (metrics != null) {
            metrics.recordTransferSucceeded();
        }

        // Record status history
        statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                null,
                transferId,
                TransferStatus.PROCESSING,
                TransferStatus.SUCCEEDED,
                "TRANSFER_POSTED",
                attemptNumber,
                envelope.correlationId(),
                now
        ));

        // Update processing attempt
        long durationMs = Duration.between(startTime, now).toMillis();
        processingAttemptRepository.updateOutcome(
                transferId,
                attemptNumber,
                "SUCCEEDED",
                null,
                null,
                now,
                durationMs
        );

        // Record processed message dedupe record
        processedMessagesRepository.insert(new ProcessedMessageRecord(
                CONSUMER_NAME,
                envelope.eventId(),
                now,
                "SUCCEEDED"
        ));

        log.info("Asynchronously completed transfer id={} on attempt {} [SUCCEEDED]", transferId, attemptNumber);
    }

    private void handleBusinessDecline(
            TransferRecord transfer,
            UUID claimToken,
            int attemptNumber,
            EventEnvelope envelope,
            String errorCode,
            String safeMessage,
            Instant startTime,
            Instant now
    ) {
        if (transfer.status() != TransferStatus.PROCESSING || !claimToken.equals(transfer.processingClaimToken())) {
            log.warn("Transfer id={} claimToken={} is stale during business decline handling; aborting.", transfer.id(), claimToken);
            return;
        }

        transferRepository.completeProcessing(transfer.id(), TransferStatus.FAILED, errorCode, safeMessage, now, now);
        if (metrics != null) {
            metrics.recordTransferBusinessDeclined();
        }

        statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                null,
                transfer.id(),
                TransferStatus.PROCESSING,
                TransferStatus.FAILED,
                errorCode,
                attemptNumber,
                envelope.correlationId(),
                now
        ));

        long durationMs = Duration.between(startTime, now).toMillis();
        processingAttemptRepository.updateOutcome(
                transfer.id(),
                attemptNumber,
                "BUSINESS_FAILED",
                errorCode,
                safeMessage,
                now,
                durationMs
        );

        processedMessagesRepository.insert(new ProcessedMessageRecord(
                CONSUMER_NAME,
                envelope.eventId(),
                now,
                "BUSINESS_FAILED"
        ));

        log.warn("Permanent business decline on transfer id={} [code={}]", transfer.id(), errorCode);
    }

    public void handleTechnicalFailure(
            UUID transferId,
            UUID claimToken,
            int attemptNumber,
            EventEnvelope envelope,
            Exception cause,
            Instant startTime
    ) {
        Instant now = Instant.now();
        long durationMs = Duration.between(startTime, now).toMillis();
        String safeError = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();

        // Finding 4: Lock transfer and verify claim ownership before modifying state
        Optional<TransferRecord> transferOpt = transferRepository.findByIdForUpdate(transferId);
        if (transferOpt.isEmpty()) {
            log.warn("Transfer id={} disappeared during failure handling; aborting failure update.", transferId);
            return;
        }

        TransferRecord transfer = transferOpt.get();

        // Terminal-state guard: If transfer has already reached SUCCEEDED or FAILED, stale worker must do nothing
        if (transfer.status() == TransferStatus.SUCCEEDED || transfer.status() == TransferStatus.FAILED) {
            log.warn("Transfer id={} is already terminal ({}); stale worker with claimToken={} must not modify state.",
                    transferId, transfer.status(), claimToken);
            return;
        }

        // Fencing check: Claim token and PROCESSING status must match current claim
        if (transfer.status() != TransferStatus.PROCESSING || !claimToken.equals(transfer.processingClaimToken())) {
            log.warn("Worker claim token {} is stale on transfer id={} (current claim={}, status={}); ignoring failure handling.",
                    claimToken, transferId, transfer.processingClaimToken(), transfer.status());
            return;
        }

        if (attemptNumber < maxAttempts) {
            // Schedule durable retry via outbox
            transferRepository.resetToPending(transferId, now);

            processingAttemptRepository.updateOutcome(
                    transferId,
                    attemptNumber,
                    "RETRY_SCHEDULED",
                    "TECHNICAL_ERROR",
                    safeError,
                    now,
                    durationMs
            );

            statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                    null,
                    transferId,
                    TransferStatus.PROCESSING,
                    TransferStatus.PENDING,
                    "RETRY_SCHEDULED",
                    attemptNumber,
                    envelope.correlationId(),
                    now
            ));

            Duration backoff = calculateBackoff(attemptNumber);
            Instant retryAvailableAt = now.plus(backoff);

            String retryPayload;
            try {
                retryPayload = objectMapper.writeValueAsString(Map.of(
                        "transferId", transferId.toString(),
                        "retryAttempt", attemptNumber + 1
                ));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize retry payload", e);
            }

            long nextSeq = outboxRepository.getNextAggregateSequence("transfer", transferId);

            OutboxEventRecord retryOutboxEvent = new OutboxEventRecord(
                    UUID.randomUUID(),
                    "transfer",
                    transferId,
                    nextSeq,
                    "transfer.retry",
                    1,
                    retryPayload,
                    envelope.correlationId(),
                    retryAvailableAt,
                    now,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null
            );
            outboxRepository.insert(retryOutboxEvent);

            processedMessagesRepository.insert(new ProcessedMessageRecord(
                    CONSUMER_NAME,
                    envelope.eventId(),
                    now,
                    "RETRY_SCHEDULED"
            ));

            if (metrics != null) {
                metrics.recordConsumerEventRetried();
            }

            log.info("Scheduled durable retry for transfer id={} [attempt={}] availableAt={}",
                    transferId, attemptNumber + 1, retryAvailableAt);

        } else {
            // Max attempts exhausted: set terminal FAILED and record DLQ in outbox in the same transaction
            transferRepository.completeProcessing(
                    transferId,
                    TransferStatus.FAILED,
                    "RETRY_EXHAUSTED",
                    "Max processing attempts reached (" + maxAttempts + ")",
                    now,
                    now
            );
            if (metrics != null) {
                metrics.recordTransferFailed();
                metrics.recordConsumerEventDlq();
            }

            processingAttemptRepository.updateOutcome(
                    transferId,
                    attemptNumber,
                    "EXHAUSTED",
                    "RETRY_EXHAUSTED",
                    "Max attempts exhausted: " + safeError,
                    now,
                    durationMs
            );

            statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                    null,
                    transferId,
                    TransferStatus.PROCESSING,
                    TransferStatus.FAILED,
                    "RETRY_EXHAUSTED",
                    attemptNumber,
                    envelope.correlationId(),
                    now
            ));

            processedMessagesRepository.insert(new ProcessedMessageRecord(
                    CONSUMER_NAME,
                    envelope.eventId(),
                    now,
                    "EXHAUSTED"
            ));

            deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                    transferCommandsTopic,
                    envelope.eventId(),
                    transferId,
                    "RETRY_EXHAUSTED",
                    "Max processing attempts reached (" + maxAttempts + "): " + safeError,
                    attemptNumber,
                    now,
                    envelope.payload().toString()
            ));

            log.warn("Transfer id={} exhausted max attempts ({}); marked FAILED and recorded in DLQ outbox.", transferId, maxAttempts);
        }
    }

    private Duration calculateBackoff(int attemptNumber) {
        long multiplier = 1L << Math.min(attemptNumber - 1, 6);
        long delayMillis = initialBackoff.toMillis() * multiplier;
        long cappedMillis = Math.min(delayMillis, maxBackoff.toMillis());
        long jitterMillis = random.nextLong(Math.max(1, cappedMillis / 4));
        return Duration.ofMillis(cappedMillis + jitterMillis);
    }
}
