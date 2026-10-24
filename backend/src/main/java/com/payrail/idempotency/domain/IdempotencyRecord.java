package com.payrail.idempotency.domain;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        String idempotencyKey,
        String requestHash,
        UUID transferId,
        Instant createdAt
) {
}
