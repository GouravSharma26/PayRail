package com.payrail.transfer.domain;

import java.time.Instant;
import java.util.UUID;

public record TransferStatusHistoryRecord(
        Long id,
        UUID transferId,
        TransferStatus fromStatus,
        TransferStatus toStatus,
        String reasonCode,
        Integer attemptNumber,
        UUID correlationId,
        Instant occurredAt
) {
    public static TransferStatusHistoryRecord initialSubmission(UUID transferId, UUID correlationId) {
        return new TransferStatusHistoryRecord(
                null,
                transferId,
                null,
                TransferStatus.PENDING,
                "TRANSFER_SUBMITTED",
                0,
                correlationId,
                Instant.now()
        );
    }
}
