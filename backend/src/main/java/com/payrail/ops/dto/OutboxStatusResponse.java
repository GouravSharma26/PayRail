package com.payrail.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outbox event backlog summary")
public record OutboxStatusResponse(
        @Schema(description = "Count of events awaiting initial publication", example = "0")
        long pendingCount,

        @Schema(description = "Count of events that encountered temporary publish failure and await retry", example = "0")
        long failedCount,

        @Schema(description = "Count of events that permanently exhausted publish retries", example = "0")
        long exhaustedCount,

        @Schema(description = "Total unpublished events (pending + failed + exhausted)", example = "0")
        long totalUnpublished,

        @Schema(description = "Age in seconds of the oldest unpublished event, or null if outbox is empty", example = "4")
        Long oldestUnpublishedAgeSeconds,

        @Schema(description = "Whether the outbox publisher scheduler is enabled", example = "true")
        boolean publisherEnabled
) {}
