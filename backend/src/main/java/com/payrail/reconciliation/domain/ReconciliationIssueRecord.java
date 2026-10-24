package com.payrail.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationIssueRecord(
        UUID id,
        UUID runId,
        String issueType,
        String severity,
        UUID accountId,
        UUID transferId,
        UUID outboxEventId,
        Instant detectedAt,
        String detailsJson,
        String status,
        String resolutionNote
) {
}
