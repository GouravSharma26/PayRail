package com.payrail.transfer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.payrail.common.util.AmountParser;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Transfer representation")
public record TransferResponse(
        @Schema(description = "Transfer identifier")
        UUID id,

        @Schema(description = "Source account identifier")
        UUID sourceAccountId,

        @Schema(description = "Destination account identifier")
        UUID destinationAccountId,

        @Schema(description = "Amount formatted as decimal string", example = "125.40")
        String amount,

        @Schema(description = "3-character currency code")
        String currency,

        @Schema(description = "Lifecycle status")
        TransferStatus status,

        @Schema(description = "Safe failure code if terminal FAILED", nullable = true)
        String failureCode,

        @Schema(description = "Safe failure message if terminal FAILED", nullable = true)
        String failureMessage,

        @Schema(description = "Creation timestamp")
        Instant createdAt,

        @Schema(description = "Last update timestamp")
        Instant updatedAt,

        @Schema(description = "Terminal completion timestamp", nullable = true)
        Instant completedAt,

        @Schema(description = "Total number of processing attempts executed")
        int processingAttemptCount,

        @Schema(description = "Correlation identifier")
        UUID correlationId
) {
    public static TransferResponse fromRecord(TransferRecord record) {
        return new TransferResponse(
                record.id(),
                record.sourceAccountId(),
                record.destinationAccountId(),
                AmountParser.formatAmount(record.amount()),
                record.currency(),
                record.status(),
                record.failureCode(),
                record.failureMessage(),
                record.createdAt(),
                record.updatedAt(),
                record.completedAt(),
                record.processingAttemptCount(),
                record.correlationId()
        );
    }
}
