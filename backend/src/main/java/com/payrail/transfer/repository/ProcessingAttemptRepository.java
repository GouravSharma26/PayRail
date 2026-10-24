package com.payrail.transfer.repository;

import com.payrail.transfer.domain.ProcessingAttemptRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingAttemptRepository {

    void insert(ProcessingAttemptRecord record);

    List<ProcessingAttemptRecord> findByTransferId(UUID transferId);

    Optional<ProcessingAttemptRecord> findByTransferIdAndAttempt(UUID transferId, int attemptNumber);

    void updateOutcome(
            UUID transferId,
            int attemptNumber,
            String outcome,
            String errorCode,
            String safeErrorMessage,
            Instant finishedAt,
            Long durationMs
    );

    boolean finalizeStaleAttempt(
            UUID transferId,
            int attemptNumber,
            UUID claimToken,
            String outcome,
            String errorCode,
            String safeErrorMessage,
            Instant finishedAt
    );
}
