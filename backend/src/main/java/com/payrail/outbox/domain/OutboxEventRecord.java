package com.payrail.outbox.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventRecord(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        long aggregateSequence,
        String eventType,
        int schemaVersion,
        String payloadJson,
        UUID correlationId,
        Instant availableAt,
        Instant createdAt,
        Instant publishedAt,
        int publishAttempts,
        UUID leaseToken,
        Instant leaseUntil,
        String lastErrorCode,
        String lastErrorMessage
) {
    public static OutboxEventRecord createPending(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            long aggregateSequence,
            String eventType,
            int schemaVersion,
            String payloadJson,
            UUID correlationId
    ) {
        Instant now = Instant.now();
        return new OutboxEventRecord(
                eventId,
                aggregateType,
                aggregateId,
                aggregateSequence,
                eventType,
                schemaVersion,
                payloadJson,
                correlationId,
                now,
                now,
                null,
                0,
                null,
                null,
                null,
                null
        );
    }
}
