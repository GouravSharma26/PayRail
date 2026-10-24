package com.payrail.common.util;

import com.payrail.common.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Utility for parsing and formatting monetary amounts strictly.
 * Invariant: Binary float/double must never be used. Scale must be <= 2, precision <= 19.
 */
public final class AmountParser {

    private static final Pattern VALID_DECIMAL_PATTERN = Pattern.compile("^\\d+(\\.\\d{1,2})?$");
    private static final int MAX_PRECISION = 19;
    private static final int REQUIRED_SCALE = 2;

    private AmountParser() {
    }

    /**
     * Parses a string representation of an amount into a validated BigDecimal with scale 2.
     *
     * @param fieldName  the API field name for validation error reporting
     * @param rawAmount  the raw string value from the request
     * @param allowZero  whether zero is allowed (e.g., opening balance >= 0 vs transfer > 0)
     * @return BigDecimal normalized to scale 2
     */
    public static BigDecimal parseAmount(String fieldName, String rawAmount, boolean allowZero) {
        if (rawAmount == null || rawAmount.isBlank()) {
            throw new ValidationException(fieldName, "REQUIRED_FIELD", "Amount is required.");
        }

        String trimmed = rawAmount.trim();

        // Reject exponential notation, multiple decimal points, leading signs (+/-), and excess decimals
        if (!VALID_DECIMAL_PATTERN.matcher(trimmed).matches()) {
            throw new ValidationException(fieldName, "INVALID_FORMAT",
                    "Amount must be a positive decimal number with at most two fractional digits (e.g., '125.40').");
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            throw new ValidationException(fieldName, "INVALID_FORMAT", "Amount format is invalid.");
        }

        if (parsed.precision() > MAX_PRECISION) {
            throw new ValidationException(fieldName, "PRECISION_EXCEEDED",
                    "Amount precision exceeds maximum allowed limit of " + MAX_PRECISION + " digits.");
        }

        if (parsed.scale() > REQUIRED_SCALE) {
            throw new ValidationException(fieldName, "INVALID_SCALE",
                    "Amount must have at most two decimal places. Silent rounding is prohibited.");
        }

        BigDecimal normalized = parsed.setScale(REQUIRED_SCALE, RoundingMode.UNNECESSARY);

        if (allowZero) {
            if (normalized.compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException(fieldName, "NEGATIVE_AMOUNT", "Amount must be greater than or equal to zero.");
            }
        } else {
            if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException(fieldName, "NON_POSITIVE_AMOUNT", "Amount must be strictly positive.");
            }
        }

        return normalized;
    }

    /**
     * Formats a BigDecimal to a standard two-decimal string.
     */
    public static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(REQUIRED_SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }
}
