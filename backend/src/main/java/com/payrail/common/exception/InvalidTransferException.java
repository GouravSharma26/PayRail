package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidTransferException extends PayRailException {

    public InvalidTransferException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INVALID_TRANSFER");
    }
}
