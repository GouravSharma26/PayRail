package com.payrail.account.dto;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.common.util.AmountParser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Account details representation")
public record AccountResponse(
        @Schema(description = "Unique account identifier")
        UUID id,

        @Schema(description = "Account display name")
        String displayName,

        @Schema(description = "3-character currency code")
        String currency,

        @Schema(description = "Immutable synthetic opening balance", example = "1000.00")
        String openingBalance,

        @Schema(description = "Current balance in decimal string format", example = "1000.00")
        String currentBalance,

        @Schema(description = "Lifecycle status")
        AccountStatus status,

        @Schema(description = "Creation timestamp")
        Instant createdAt,

        @Schema(description = "Last update timestamp")
        Instant updatedAt
) {
    public static AccountResponse fromRecord(AccountRecord record) {
        return new AccountResponse(
                record.id(),
                record.displayName(),
                record.currency(),
                AmountParser.formatAmount(record.openingBalance()),
                AmountParser.formatAmount(record.currentBalance()),
                record.status(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
