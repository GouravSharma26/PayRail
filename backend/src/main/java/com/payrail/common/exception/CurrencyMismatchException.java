package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class CurrencyMismatchException extends PayRailException {

    public CurrencyMismatchException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH");
    }
}
