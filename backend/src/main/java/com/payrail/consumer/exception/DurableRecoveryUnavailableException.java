package com.payrail.consumer.exception;

public class DurableRecoveryUnavailableException extends RuntimeException {

    public DurableRecoveryUnavailableException(String message) {
        super(message);
    }

    public DurableRecoveryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
