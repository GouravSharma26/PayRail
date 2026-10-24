package com.payrail.outbox.repository;

import com.payrail.outbox.domain.OutboxEventRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository {

    void insert(OutboxEventRecord event);

    Optional<OutboxEventRecord> findById(UUID eventId);

    List<OutboxEventRecord> claimDueEvents(int batchSize, Duration leaseDuration, Instant now, UUID leaseToken);

    boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt);

    boolean recordPublishFailure(UUID eventId, UUID leaseToken, String errorCode, String errorMessage, Instant nextAvailableAt);

    boolean recordPublishExhausted(UUID eventId, UUID leaseToken, String errorCode, String errorMessage);

    int releaseStaleLeases(Instant now);

    List<OutboxEventRecord> findUnpublished(Instant threshold);

    long countUnpublished(Instant threshold);

    long getNextAggregateSequence(String aggregateType, UUID aggregateId);

    boolean resetForReplay(UUID eventId, Instant now);

    boolean hasUnpublishedEvents(String aggregateType, UUID aggregateId);
}
