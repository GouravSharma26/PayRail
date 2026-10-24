package com.payrail.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body to create a synthetic account")
public record CreateAccountRequest(
        @NotBlank(message = "displayName is required")
        @Size(max = 80, message = "displayName must not exceed 80 characters")
        @Schema(description = "Synthetic account label (no real personal data)", example = "Synthetic Account A")
        String displayName,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase 3-character ISO code (e.g., INR, USD, EUR)")
        @Schema(description = "3-character currency code", example = "INR")
        String currency,

        @NotBlank(message = "openingBalance is required")
        @Schema(description = "Synthetic opening balance (decimal string, non-negative)", example = "1000.00")
        String openingBalance
) {
}
