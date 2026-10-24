package com.payrail.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "High-level operational health and readiness summary")
public record SystemHealthSummaryResponse(
        @Schema(description = "Overall system operational status", example = "UP")
        String status,

        @Schema(description = "Database connectivity status", example = "UP")
        String dbStatus,

        @Schema(description = "Kafka broker connectivity status", example = "UP")
        String kafkaStatus,

        @Schema(description = "Total unpublished outbox events backlog", example = "0")
        long outboxBacklog,

        @Schema(description = "Status of the most recent reconciliation audit run", example = "CLEAN")
        String lastReconciliationStatus,

        @Schema(description = "Timestamp when the last reconciliation audit finished")
        Instant lastReconciliationTime,

        @Schema(description = "Count of currently open reconciliation issues", example = "0")
        long openReconciliationIssues,

        @Schema(description = "Timestamp when this health summary was generated")
        Instant timestamp
) {}
