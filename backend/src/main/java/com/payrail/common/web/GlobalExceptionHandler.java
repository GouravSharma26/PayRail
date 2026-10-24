package com.payrail.common.web;

import com.payrail.common.exception.PayRailException;
import com.payrail.common.exception.ValidationException;
import com.payrail.common.filter.CorrelationIdFilter;
import com.payrail.common.model.ErrorDetail;
import com.payrail.common.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * Global exception handler enforcing standard API contract error responses.
 * Invariant: Never leak SQL, stack traces, internal hostnames, or credentials in response bodies.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getDetails(),
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(new ErrorDetail(
                    fieldError.getField(),
                    "INVALID_FIELD",
                    fieldError.getDefaultMessage()
            ));
        }
        log.warn("Request body validation failed: {} error(s)", details.size());
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "The request body contains invalid or missing fields.",
                details,
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed HTTP request body: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "Malformed JSON request body.",
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        List<ErrorDetail> details = List.of(new ErrorDetail(
                ex.getName(),
                "INVALID_TYPE",
                String.format("Parameter '%s' has an invalid value.", ex.getName())
        ));
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                "Invalid request parameter format.",
                details,
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("Missing required request header: {}", ex.getHeaderName());
        List<ErrorDetail> details = List.of(new ErrorDetail(
                ex.getHeaderName(),
                "REQUIRED_HEADER",
                String.format("Header '%s' is required.", ex.getHeaderName())
        ));
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                String.format("Required header '%s' is missing.", ex.getHeaderName()),
                details,
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(com.payrail.common.exception.ReconciliationRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationRateLimit(com.payrail.common.exception.ReconciliationRateLimitException ex) {
        log.warn("Reconciliation rate limit [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ErrorResponse body = ErrorResponse.of(
                ex.getHttpStatus().value(),
                ex.getErrorCode(),
                ex.getMessage(),
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(ex.getHttpStatus())
                .header("Retry-After", String.valueOf(ex.getRemainingSeconds()))
                .body(body);
    }

    @ExceptionHandler(PayRailException.class)
    public ResponseEntity<ErrorResponse> handlePayRailException(PayRailException ex) {
        log.warn("Domain exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ErrorResponse body = ErrorResponse.of(
                ex.getHttpStatus().value(),
                ex.getErrorCode(),
                ex.getMessage(),
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled server error", ex);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An unexpected internal error occurred. Please contact system support.",
                CorrelationIdFilter.getCurrentCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
