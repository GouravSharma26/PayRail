package com.payrail.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends PayRailException {

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public ResourceNotFoundException(String resourceType, Object identifier) {
        super(String.format("%s with identifier '%s' was not found.", resourceType, identifier),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
