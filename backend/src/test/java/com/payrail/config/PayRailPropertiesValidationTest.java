package com.payrail.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayRailPropertiesValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    @DisplayName("Default PayRailProperties configuration passes all validation constraints")
    void testDefaultPropertiesValid() {
        PayRailProperties properties = new PayRailProperties();
        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Blank transfer commands topic name violates validation")
    void testBlankTopicFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getTopics().setTransferCommands("   ");

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Transfer commands topic name must not be blank"));
    }

    @Test
    @DisplayName("Blank DLQ topic name violates validation")
    void testBlankDlqTopicFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getTopics().setTransferCommandsDlq("");

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Transfer commands DLQ topic name must not be blank"));
    }

    @Test
    @DisplayName("Negative or zero outbox batch size violates validation")
    void testNonPositiveBatchSizeFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getOutbox().setBatchSize(0);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Outbox batch size must be positive"));
    }

    @Test
    @DisplayName("Excessive outbox batch size (>500) violates validation")
    void testExcessiveBatchSizeFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getOutbox().setBatchSize(1000);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Outbox batch size must not exceed 500"));
    }

    @Test
    @DisplayName("Null outbox lease duration violates validation")
    void testNullOutboxLeaseDurationFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getOutbox().setLeaseDuration(null);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Outbox lease duration must not be null"));
    }

    @Test
    @DisplayName("Excessive processing max attempts (>50) violates validation")
    void testExcessiveMaxAttemptsFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getProcessing().setMaxAttempts(100);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Processing max attempts must not exceed 50"));
    }
}
