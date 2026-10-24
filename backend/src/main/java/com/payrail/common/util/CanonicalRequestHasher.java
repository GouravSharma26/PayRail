package com.payrail.common.util;

import com.payrail.common.exception.ValidationException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utility for validating idempotency keys and computing SHA-256 canonical request hashes.
 */
public final class CanonicalRequestHasher {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[\\x21-\\x7E]{8,128}$");
    private static final String CONTRACT_VERSION = "v1";

    private CanonicalRequestHasher() {
    }

    /**
     * Validates that the provided Idempotency-Key satisfies contract requirements.
     */
    public static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("Idempotency-Key", "REQUIRED_HEADER",
                    "Header 'Idempotency-Key' is required for transfer creation.");
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new ValidationException("Idempotency-Key", "INVALID_KEY_FORMAT",
                    "Idempotency-Key must consist of 8 to 128 printable ASCII characters without whitespace.");
        }
    }

    /**
     * Computes deterministic SHA-256 canonical request hash.
     * Format: v1:<sourceAccountId>:<destinationAccountId>:<normalizedAmountString>:<currency>
     */
    public static String computeHash(UUID sourceAccountId, UUID destinationAccountId, BigDecimal normalizedAmount, String currency) {
        if (sourceAccountId == null || destinationAccountId == null || normalizedAmount == null || currency == null) {
            throw new IllegalArgumentException("Cannot compute request hash with null parameters.");
        }

        String canonicalString = CONTRACT_VERSION + ":" +
                sourceAccountId + ":" +
                destinationAccountId + ":" +
                AmountParser.formatAmount(normalizedAmount) + ":" +
                currency.toUpperCase();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Returns a safe key fingerprint for logging purposes (first 4 + SHA-256 snippet + last 4 chars).
     * Invariant: Never log raw idempotency keys.
     */
    public static String safeFingerprint(String key) {
        if (key == null || key.isBlank()) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return "key:" + hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "key:masked";
        }
    }
}
