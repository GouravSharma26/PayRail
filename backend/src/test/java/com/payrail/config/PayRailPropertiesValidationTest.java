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

    @Test
    @DisplayName("Zero outbox lease duration violates validation")
    void testZeroOutboxLeaseDurationFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getOutbox().setLeaseDuration(java.time.Duration.ZERO);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Outbox lease duration must be positive"));
    }

    @Test
    @DisplayName("Negative outbox poll interval violates validation")
    void testNegativeOutboxPollIntervalFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getOutbox().setPollInterval(java.time.Duration.ofMillis(-100));

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Outbox poll interval must be positive"));
    }

    @Test
    @DisplayName("Zero processing lease duration violates validation")
    void testZeroProcessingLeaseDurationFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getProcessing().setLeaseDuration(java.time.Duration.ZERO);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Processing lease duration must be positive"));
    }

    @Test
    @DisplayName("Negative processing initial backoff violates validation")
    void testNegativeProcessingInitialBackoffFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getProcessing().setInitialBackoff(java.time.Duration.ofSeconds(-1));

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Processing initial backoff must be positive"));
    }

    @Test
    @DisplayName("Zero recovery sweep interval violates validation")
    void testZeroRecoverySweepIntervalFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getProcessing().setRecoverySweepInterval(java.time.Duration.ZERO);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Recovery sweep interval must be positive"));
    }

    @Test
    @DisplayName("Zero reconciliation interval violates validation")
    void testZeroReconciliationIntervalFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getReconciliation().setInterval(java.time.Duration.ZERO);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Reconciliation interval must be positive"));
    }

    @Test
    @DisplayName("Negative stale processing threshold violates validation")
    void testNegativeStaleProcessingThresholdFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getReconciliation().setStaleProcessingThreshold(java.time.Duration.ofSeconds(-10));

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Stale processing threshold must be positive"));
    }

    @Test
    @DisplayName("Zero manual trigger cooldown violates validation")
    void testZeroManualTriggerCooldownFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getReconciliation().setManualTriggerCooldown(java.time.Duration.ZERO);

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Manual trigger cooldown must be positive"));
    }

    @Test
    @DisplayName("Processing maxBackoff less than initialBackoff violates validation")
    void testMaxBackoffLessThanInitialBackoffFails() {
        PayRailProperties properties = new PayRailProperties();
        properties.getProcessing().setInitialBackoff(java.time.Duration.ofSeconds(10));
        properties.getProcessing().setMaxBackoff(java.time.Duration.ofSeconds(2));

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Processing max backoff must be greater than or equal to initial backoff"));
    }

    @Test
    @DisplayName("Processing maxBackoff equal to initialBackoff passes validation")
    void testMaxBackoffEqualToInitialBackoffPasses() {
        PayRailProperties properties = new PayRailProperties();
        properties.getProcessing().setInitialBackoff(java.time.Duration.ofSeconds(5));
        properties.getProcessing().setMaxBackoff(java.time.Duration.ofSeconds(5));

        Set<ConstraintViolation<PayRailProperties>> violations = validator.validate(properties);
        assertThat(violations).isEmpty();
    }
}
