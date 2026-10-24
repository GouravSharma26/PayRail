package com.payrail.consumer.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeadLetterEnvelope(
        String sourceTopic,
        UUID originalEventId,
        UUID transferId,
        String failureCode,
        String failureMessage,
        int attemptCount,
        Instant failedAt,
        String rawPayload
) {
}
