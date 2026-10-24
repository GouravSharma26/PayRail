package com.payrail.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;

/**
 * Validates that a {@link Duration} is non-null, strictly greater than zero, and non-negative.
 */
public class PositiveDurationValidator implements ConstraintValidator<PositiveDuration, Duration> {

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        if (value == null) {
            // Null validity is governed by @NotNull
            return true;
        }
        return !value.isZero() && !value.isNegative();
    }
}
