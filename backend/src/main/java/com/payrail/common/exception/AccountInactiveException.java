package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class AccountInactiveException extends PayRailException {

    public AccountInactiveException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "ACCOUNT_INACTIVE");
    }
}
