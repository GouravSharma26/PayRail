package com.payrail.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferRecord(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String failureCode,
        String failureMessage,
        UUID correlationId,
        int processingAttemptCount,
        UUID processingClaimToken,
        Instant processingLeaseUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
    public static TransferRecord createPending(
            UUID id,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            UUID correlationId
    ) {
        Instant now = Instant.now();
        return new TransferRecord(
                id,
                sourceAccountId,
                destinationAccountId,
                amount,
                currency,
                TransferStatus.PENDING,
                null,
                null,
                correlationId,
                0,
                null,
                null,
                now,
                now,
                null
        );
    }
}
