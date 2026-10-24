package com.payrail.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

@Schema(description = "Request body to initiate a synthetic transfer")
public record CreateTransferRequest(
        @NotNull(message = "sourceAccountId is required")
        @Schema(description = "Source account identifier", example = "00000000-0000-0000-0000-000000000010")
        UUID sourceAccountId,

        @NotNull(message = "destinationAccountId is required")
        @Schema(description = "Destination account identifier", example = "00000000-0000-0000-0000-000000000020")
        UUID destinationAccountId,

        @NotBlank(message = "amount is required")
        @Schema(description = "Transfer amount as a positive decimal string (max 2 decimals)", example = "125.40")
        String amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase 3-character ISO code (e.g., INR, USD, EUR)")
        @Schema(description = "3-character currency code matching accounts", example = "INR")
        String currency
) {
}
