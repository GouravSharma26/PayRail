package com.payrail.common.util;

import com.payrail.common.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalRequestHasherTest {

    @Test
    @DisplayName("Should compute deterministic SHA-256 hash from canonical inputs")
    void shouldComputeDeterministicHash() {
        UUID src = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID dst = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BigDecimal amount = new BigDecimal("125.40");
        String currency = "INR";

        String hash1 = CanonicalRequestHasher.computeHash(src, dst, amount, currency);
        String hash2 = CanonicalRequestHasher.computeHash(src, dst, amount, currency);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);

        // Different amount must yield different hash
        String hashDifferentAmount = CanonicalRequestHasher.computeHash(src, dst, new BigDecimal("125.41"), currency);
        assertThat(hash1).isNotEqualTo(hashDifferentAmount);

        // Case insensitivity for currency normalization
        String hashLowerCurrency = CanonicalRequestHasher.computeHash(src, dst, amount, "inr");
        assertThat(hash1).isEqualTo(hashLowerCurrency);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "key-12345678",
            "12345678",
            "valid-idempotency-key-sample-123456",
            "MyUniqueIdempotencyKey_2026-10-24"
    })
    @DisplayName("Should accept valid idempotency keys (8 to 128 printable ASCII characters)")
    void shouldAcceptValidIdempotencyKeys(String key) {
        // Must not throw
        CanonicalRequestHasher.validateIdempotencyKey(key);
    }

    @Test
    @DisplayName("Should accept boundary lengths 8 and 128 characters")
    void shouldAcceptBoundaryLengths() {
        CanonicalRequestHasher.validateIdempotencyKey("12345678");
        CanonicalRequestHasher.validateIdempotencyKey("a".repeat(128));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",
            "1234567",
            "",
            "   ",
            "key with spaces in middle",
            "key\twith\ttab",
            "key\nwith\nnewline"
    })
    @DisplayName("Should reject invalid idempotency keys (too short, whitespace, non-printable)")
    void shouldRejectInvalidIdempotencyKeys(String key) {
        assertThatThrownBy(() -> CanonicalRequestHasher.validateIdempotencyKey(key))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Should reject null idempotency key")
    void shouldRejectNullIdempotencyKey() {
        assertThatThrownBy(() -> CanonicalRequestHasher.validateIdempotencyKey(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("Should produce safe masked key fingerprint without leaking raw key")
    void shouldProduceSafeKeyFingerprint() {
        String rawKey = "SuperSecretIdempotencyKey12345678";
        String fingerprint = CanonicalRequestHasher.safeFingerprint(rawKey);

        assertThat(fingerprint).startsWith("key:");
        assertThat(fingerprint).doesNotContain(rawKey);
        assertThat(CanonicalRequestHasher.safeFingerprint(null)).isEqualTo("empty");
        assertThat(CanonicalRequestHasher.safeFingerprint("")).isEqualTo("empty");
    }
}
