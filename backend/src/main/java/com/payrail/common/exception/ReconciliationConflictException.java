package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class ReconciliationConflictException extends PayRailException {

    public ReconciliationConflictException(String message) {
        super(message, HttpStatus.CONFLICT, "RECONCILIATION_ALREADY_RUNNING");
    }
}
