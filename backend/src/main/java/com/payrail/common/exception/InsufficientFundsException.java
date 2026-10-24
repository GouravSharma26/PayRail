package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends PayRailException {

    public InsufficientFundsException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS");
    }
}
