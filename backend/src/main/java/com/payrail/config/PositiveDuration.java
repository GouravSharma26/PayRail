package com.payrail.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint annotation validating that a {@link java.time.Duration} is strictly positive
 * (non-null, non-zero, and non-negative).
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PositiveDurationValidator.class)
@Documented
public @interface PositiveDuration {

    String message() default "Duration must be positive (greater than zero)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
