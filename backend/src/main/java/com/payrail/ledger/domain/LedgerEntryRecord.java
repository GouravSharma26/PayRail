package com.payrail.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable double-entry financial ledger record.
 */
public record LedgerEntryRecord(
        UUID id,
        UUID transferId,
        UUID accountId,
        LedgerLeg leg,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
    public LedgerEntryRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(leg, "leg must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be strictly positive: " + amount);
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Ledger entry amount scale must be <= 2: " + amount);
        }
    }
}
