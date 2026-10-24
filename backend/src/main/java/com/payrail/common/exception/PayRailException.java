package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class PayRailException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String errorCode;

    public PayRailException(String message, HttpStatus httpStatus, String errorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public PayRailException(String message, Throwable cause, HttpStatus httpStatus, String errorCode) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
