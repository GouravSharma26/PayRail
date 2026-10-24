package com.payrail.common.exception;

import com.payrail.common.model.ErrorDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

public class ValidationException extends PayRailException {

    private final List<ErrorDetail> details;

    public ValidationException(String message, List<ErrorDetail> details) {
        super(message, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        this.details = details;
    }

    public ValidationException(String field, String code, String message) {
        this(message, List.of(new ErrorDetail(field, code, message)));
    }

    public List<ErrorDetail> getDetails() {
        return details;
    }
}
