package com.payrail.consumer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.repository.ProcessingAttemptRepository;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProcessingRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingRecoveryService.class);

    private final TransferRepository transferRepository;
    private final OutboxRepository outboxRepository;
    private final TransferStatusHistoryRepository statusHistoryRepository;
    private final ProcessingAttemptRepository processingAttemptRepository;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Value("${payrail.processing.recovery-sweep-enabled:true}")
    private boolean recoverySweepEnabled;

    @Value("${payrail.processing.max-attempts:5}")
    private int maxAttempts;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    @Value("${payrail.processing.stale-pending-threshold:30s}")
    private Duration stalePendingThreshold;

    public ProcessingRecoveryService(
            TransferRepository transferRepository,
            OutboxRepository outboxRepository,
            TransferStatusHistoryRepository statusHistoryRepository,
            ProcessingAttemptRepository processingAttemptRepository,
            DeadLetterPublisher deadLetterPublisher,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.transferRepository = transferRepository;
        this.outboxRepository = outboxRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.processingAttemptRepository = processingAttemptRepository;
        this.deadLetterPublisher = deadLetterPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${payrail.processing.recovery-sweep-interval:15s}")
    public int scheduledSweep() {
        if (!recoverySweepEnabled) {
            return 0;
        }
        return runRecoverySweep();
    }

    /**
     * Executes a full recovery sweep across stale processing leases, stale outbox leases,
     * and orphaned pending transfers. Can be called directly by scheduler, tests, or ops.
     */
    public int runRecoverySweep() {
        int totalRecovered = 0;
        totalRecovered += sweepStaleProcessingLeases();
        totalRecovered += sweepStaleOutboxLeases();
        totalRecovered += sweepStalePendingTransfers();
        return totalRecovered;
    }

    public int sweepStaleProcessingLeases() {
        Instant now = Instant.now();
        List<TransferRecord> staleTransfers = transferRepository.findStaleProcessingLeases(now);
        if (staleTransfers.isEmpty()) {
            return 0;
        }

        log.info("Found {} stale processing lease(s) requiring recovery sweep", staleTransfers.size());
        int recovered = 0;

        for (TransferRecord stale : staleTransfers) {
            try {
                Boolean result = transactionTemplate.execute(status -> recoverStaleTransfer(stale.id()));
                if (Boolean.TRUE.equals(result)) {
                    recovered++;
                }
            } catch (Exception e) {
                log.error("Failed to recover stale processing lease for transfer id={} [{}]: {}",
                        stale.id(), e.getClass().getSimpleName(), e.getMessage(), e);
                // Individual recovery failure rolls back that candidate's complete transaction and does not abort remaining candidates
            }
        }
        return recovered;
    }

    private boolean recoverStaleTransfer(UUID transferId) {
        Instant now = Instant.now();
        Optional<TransferRecord> transferOpt = transferRepository.findByIdForUpdate(transferId);
        if (transferOpt.isEmpty()) {
            return false;
        }

        TransferRecord transfer = transferOpt.get();
        if (transfer.status() != TransferStatus.PROCESSING) {
            return false;
        }

        if (transfer.processingLeaseUntil() != null && transfer.processingLeaseUntil().isAfter(now)) {
            return false; // lease renewed in the interim
        }

        if (transfer.processingAttemptCount() >= maxAttempts) {
            finalizeStaleAttemptOrThrow(
                    transfer,
                    "EXHAUSTED",
                    "LEASE_EXPIRED_EXHAUSTED",
                    "Processing lease expired after reaching max attempts (" + maxAttempts + ")",
                    now
            );

            transferRepository.completeProcessing(
                    transferId,
                    TransferStatus.FAILED,
                    "LEASE_EXPIRED_EXHAUSTED",
                    "Processing lease expired after reaching max attempts (" + maxAttempts + ")",
                    now,
                    now
            );

            statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                    null,
                    transferId,
                    TransferStatus.PROCESSING,
                    TransferStatus.FAILED,
                    "LEASE_EXPIRED_EXHAUSTED",
                    transfer.processingAttemptCount(),
                    transfer.correlationId(),
                    now
            ));

            deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                    transferCommandsTopic,
                    null,
                    transferId,
                    "LEASE_EXPIRED_EXHAUSTED",
                    "Stale processing lease expired and max attempts exhausted",
                    transfer.processingAttemptCount(),
                    now,
                    null
            ));

            log.warn("Stale transfer id={} exhausted max attempts; marked FAILED and sent to DLQ.", transferId);
            return true;

        } else {
            finalizeStaleAttemptOrThrow(
                    transfer,
                    "RETRY_SCHEDULED",
                    "STALE_LEASE_RECOVERED",
                    "Stale processing lease recovered and retry scheduled",
                    now
            );

            // Reset to PENDING and enqueue retry outbox event
            transferRepository.resetToPending(transferId, now);

            statusHistoryRepository.insert(new TransferStatusHistoryRecord(
                    null,
                    transferId,
                    TransferStatus.PROCESSING,
                    TransferStatus.PENDING,
                    "STALE_LEASE_RECOVERED",
                    transfer.processingAttemptCount(),
                    transfer.correlationId(),
                    now
            ));

            String retryPayload;
            try {
                retryPayload = objectMapper.writeValueAsString(Map.of(
                        "transferId", transferId.toString(),
                        "recoveredAttempt", transfer.processingAttemptCount() + 1
                ));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize recovery retry payload", e);
            }

            long nextSeq = outboxRepository.getNextAggregateSequence("transfer", transferId);

            OutboxEventRecord recoveryEvent = new OutboxEventRecord(
                    UUID.randomUUID(),
                    "transfer",
                    transferId,
                    nextSeq,
                    "transfer.recovery",
                    1,
                    retryPayload,
                    transfer.correlationId(),
                    now,
                    now,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null
            );
            outboxRepository.insert(recoveryEvent);

            log.info("Recovered stale processing lease on transfer id={}; returned to PENDING and enqueued outbox recovery event seq={}.", transferId, nextSeq);
            return true;
        }
    }

    /**
     * Finalizes the exact attempt that owned the expired lease before changing transfer state.
     * A missing token/attempt or a failed fenced update aborts the enclosing transaction so the
     * transfer, history, and outbox remain unchanged rather than leaving a dangling IN_PROGRESS row.
     */
    private void finalizeStaleAttemptOrThrow(
            TransferRecord transfer,
            String outcome,
            String errorCode,
            String safeErrorMessage,
            Instant finishedAt
    ) {
        int attemptNumber = transfer.processingAttemptCount();
        UUID claimToken = transfer.processingClaimToken();
        if (attemptNumber <= 0 || claimToken == null) {
            throw new IllegalStateException(
                    "Cannot finalize stale processing attempt for transfer id=" + transfer.id()
                            + ": missing positive attempt number or claim token"
            );
        }

        boolean finalized = processingAttemptRepository.finalizeStaleAttempt(
                transfer.id(),
                attemptNumber,
                claimToken,
                outcome,
                errorCode,
                safeErrorMessage,
                finishedAt
        );
        if (!finalized) {
            throw new IllegalStateException(
                    "Failed to finalize stale processing attempt for transfer id=" + transfer.id()
                            + " attempt=" + attemptNumber + "; refusing recovery transition"
            );
        }
    }

    public int sweepStaleOutboxLeases() {
        Instant now = Instant.now();
        int released = outboxRepository.releaseStaleLeases(now);
        if (released > 0) {
            log.info("Released {} stale outbox publisher lease(s)", released);
        }
        return released;
    }

    public int sweepStalePendingTransfers() {
        Instant threshold = Instant.now().minus(stalePendingThreshold);
        List<TransferRecord> abandonedPending = transferRepository.findPendingWithoutOutbox(threshold);
        if (abandonedPending.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (TransferRecord pending : abandonedPending) {
            try {
                Boolean result = transactionTemplate.execute(status -> recoverPendingTransfer(pending.id()));
                if (Boolean.TRUE.equals(result)) {
                    recovered++;
                }
            } catch (Exception e) {
                log.error("Failed to recover pending transfer id={}: {}", pending.id(), e.getMessage(), e);
                // Individual recovery failure does not abort remaining candidates
            }
        }
        if (recovered > 0) {
            log.info("Recovered {} abandoned pending transfer(s) by enqueuing outbox events", recovered);
        }
        return recovered;
    }

    private boolean recoverPendingTransfer(UUID transferId) {
        Instant now = Instant.now();
        // 1. Lock and re-read transfer
        Optional<TransferRecord> transferOpt = transferRepository.findByIdForUpdate(transferId);
        if (transferOpt.isEmpty()) {
            return false;
        }

        TransferRecord transfer = transferOpt.get();
        // 2. Confirm it is still PENDING
        if (transfer.status() != TransferStatus.PENDING) {
            return false;
        }

        // 3. Confirm it still needs recovery: no active or unpublished outbox event exists
        if (outboxRepository.hasUnpublishedEvents("transfer", transferId)) {
            return false;
        }

        // 4. Allocate aggregate sequence that cannot collide with existing events
        long nextSeq = outboxRepository.getNextAggregateSequence("transfer", transferId);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("transferId", transferId.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize recovery payload", e);
        }

        OutboxEventRecord recoveryEvent = new OutboxEventRecord(
                UUID.randomUUID(),
                "transfer",
                transferId,
                nextSeq,
                "transfer.requested",
                1,
                payload,
                transfer.correlationId(),
                now,
                now,
                null,
                0,
                null,
                null,
                null,
                null
        );

        outboxRepository.insert(recoveryEvent);
        log.info("Enqueued recovery outbox event id={} seq={} for pending transfer id={}",
                recoveryEvent.eventId(), nextSeq, transferId);
        return true;
    }
}
