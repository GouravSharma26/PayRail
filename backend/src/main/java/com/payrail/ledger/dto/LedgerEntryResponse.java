package com.payrail.ledger.dto;

import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Immutable double-entry financial ledger entry")
public record LedgerEntryResponse(
        @Schema(description = "Ledger entry UUID", example = "00000000-0000-0000-0000-000000000001")
        UUID id,

        @Schema(description = "Associated transfer UUID", example = "00000000-0000-0000-0000-000000000099")
        UUID transferId,

        @Schema(description = "Debited or credited synthetic account UUID", example = "00000000-0000-0000-0000-000000000010")
        UUID accountId,

        @Schema(description = "Financial leg type", example = "DEBIT")
        LedgerLeg leg,

        @Schema(description = "Monetary amount as two-decimal string", example = "125.40")
        String amount,

        @Schema(description = "Three-letter currency code", example = "INR")
        String currency,

        @Schema(description = "Entry creation timestamp in UTC")
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntryRecord record) {
        return new LedgerEntryResponse(
                record.id(),
                record.transferId(),
                record.accountId(),
                record.leg(),
                record.amount().toPlainString(),
                record.currency(),
                record.createdAt()
        );
    }
}
