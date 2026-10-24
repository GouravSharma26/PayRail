package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class ReconciliationRateLimitException extends PayRailException {

    private final long remainingSeconds;

    public ReconciliationRateLimitException(String message, long remainingSeconds) {
        super(message, HttpStatus.TOO_MANY_REQUESTS, "RECONCILIATION_COOLDOWN_ACTIVE");
        this.remainingSeconds = remainingSeconds;
    }

    public long getRemainingSeconds() {
        return remainingSeconds;
    }
}
