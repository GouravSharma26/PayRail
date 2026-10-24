package com.payrail.transfer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Transfer status transition history entry")
public record TransferStatusHistoryResponse(
        Long id,
        UUID transferId,
        TransferStatus fromStatus,
        TransferStatus toStatus,
        String reasonCode,
        Integer attemptNumber,
        UUID correlationId,
        Instant occurredAt
) {
    public static TransferStatusHistoryResponse fromRecord(TransferStatusHistoryRecord record) {
        return new TransferStatusHistoryResponse(
                record.id(),
                record.transferId(),
                record.fromStatus(),
                record.toStatus(),
                record.reasonCode(),
                record.attemptNumber(),
                record.correlationId(),
                record.occurredAt()
        );
    }
}
