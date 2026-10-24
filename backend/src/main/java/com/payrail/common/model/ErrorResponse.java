package com.payrail.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<ErrorDetail> details,
        String correlationId
) {
    public static ErrorResponse of(int status, String code, String message, List<ErrorDetail> details, String correlationId) {
        return new ErrorResponse(Instant.now(), status, code, message, details, correlationId);
    }

    public static ErrorResponse of(int status, String code, String message, String correlationId) {
        return new ErrorResponse(Instant.now(), status, code, message, null, correlationId);
    }
}
