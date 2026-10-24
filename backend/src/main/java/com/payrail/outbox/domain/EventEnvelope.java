package com.payrail.outbox.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard versioned event envelope for PayRail asynchronous messaging.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        long aggregateSequence,
        UUID correlationId,
        JsonNode payload
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonIgnore
    public boolean isValid() {
        return eventId != null
                && eventType != null && !eventType.isBlank()
                && schemaVersion > 0
                && aggregateType != null && !aggregateType.isBlank()
                && aggregateId != null
                && correlationId != null
                && payload != null;
    }
}
