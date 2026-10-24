package com.payrail.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunRecord(
        UUID id,
        Instant startedAt,
        Instant finishedAt,
        String status,
        long scannedTransfersCount,
        long scannedAccountsCount,
        int issueCount,
        String triggerType,
        String summary
) {
}
