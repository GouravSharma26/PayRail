package com.payrail.account.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity and persistence row representation of an Account.
 */
public record AccountRecord(
        UUID id,
        String displayName,
        String currency,
        BigDecimal openingBalance,
        BigDecimal currentBalance,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
