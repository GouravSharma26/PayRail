package com.payrail.transfer.repository;

import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.TransferSearchCriteria;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {

    void insert(TransferRecord transfer);

    Optional<TransferRecord> findById(UUID id);

    List<TransferRecord> search(TransferSearchCriteria criteria);

    long count(TransferSearchCriteria criteria);

    Optional<TransferRecord> findByIdForUpdate(UUID id);

    int updateStatus(
            UUID id,
            TransferStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt,
            Instant updatedAt
    );

    boolean claimForProcessing(
            UUID id,
            UUID claimToken,
            Instant leaseUntil,
            Instant now
    );

    int completeProcessing(
            UUID id,
            TransferStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt,
            Instant updatedAt
    );

    int resetToPending(
            UUID id,
            Instant updatedAt
    );

    List<TransferRecord> findStaleProcessingLeases(Instant threshold);

    List<TransferRecord> findPendingWithoutOutbox(Instant threshold);
}
