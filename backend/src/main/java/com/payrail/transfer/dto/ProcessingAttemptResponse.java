package com.payrail.transfer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.payrail.transfer.domain.ProcessingAttemptRecord;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Safe processing attempt details")
public record ProcessingAttemptResponse(
        UUID id,
        UUID transferId,
        int attemptNumber,
        String outcome,
        String errorCode,
        String safeErrorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs
) {
    public static ProcessingAttemptResponse fromRecord(ProcessingAttemptRecord record) {
        return new ProcessingAttemptResponse(
                record.id(),
                record.transferId(),
                record.attemptNumber(),
                record.outcome(),
                record.errorCode(),
                record.safeErrorMessage(),
                record.startedAt(),
                record.finishedAt(),
                record.durationMs()
        );
    }
}
