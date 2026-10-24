package com.payrail.transfer.domain;

import java.time.Instant;
import java.util.UUID;

public record ProcessingAttemptRecord(
        UUID id,
        UUID transferId,
        int attemptNumber,
        UUID claimToken,
        String outcome,
        String errorCode,
        String safeErrorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs
) {
}
