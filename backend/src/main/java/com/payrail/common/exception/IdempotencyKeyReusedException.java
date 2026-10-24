package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyReusedException extends PayRailException {

    public IdempotencyKeyReusedException(String message) {
        super(message, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
    }
}
