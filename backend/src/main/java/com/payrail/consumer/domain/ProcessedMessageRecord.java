package com.payrail.consumer.domain;

import java.time.Instant;
import java.util.UUID;

public record ProcessedMessageRecord(
        String consumerName,
        UUID eventId,
        Instant processedAt,
        String outcome
) {
}
