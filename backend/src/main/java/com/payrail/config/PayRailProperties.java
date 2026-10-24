package com.payrail.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Validated configuration properties for the PayRail simulator.
 * Binding is fail-fast: the application will not start if any constraint is violated.
 */
@ConfigurationProperties(prefix = "payrail")
@Validated
public class PayRailProperties {

    @Valid
    @NotNull
    private TopicsProperties topics = new TopicsProperties();

    @Valid
    @NotNull
    private OutboxProperties outbox = new OutboxProperties();

    @Valid
    @NotNull
    private ProcessingProperties processing = new ProcessingProperties();

    @Valid
    @NotNull
    private ReconciliationProperties reconciliation = new ReconciliationProperties();

    public TopicsProperties getTopics() {
        return topics;
    }

    public void setTopics(TopicsProperties topics) {
        this.topics = topics;
    }

    public OutboxProperties getOutbox() {
        return outbox;
    }

    public void setOutbox(OutboxProperties outbox) {
        this.outbox = outbox;
    }

    public ProcessingProperties getProcessing() {
        return processing;
    }

    public void setProcessing(ProcessingProperties processing) {
        this.processing = processing;
    }

    public ReconciliationProperties getReconciliation() {
        return reconciliation;
    }

    public void setReconciliation(ReconciliationProperties reconciliation) {
        this.reconciliation = reconciliation;
    }

    public static class TopicsProperties {
        @NotBlank(message = "Transfer commands topic name must not be blank")
        private String transferCommands = "payrail.transfer-commands.v1";

        @NotBlank(message = "Transfer commands DLQ topic name must not be blank")
        private String transferCommandsDlq = "payrail.transfer-commands.dlq.v1";

        public String getTransferCommands() {
            return transferCommands;
        }

        public void setTransferCommands(String transferCommands) {
            this.transferCommands = transferCommands;
        }

        public String getTransferCommandsDlq() {
            return transferCommandsDlq;
        }

        public void setTransferCommandsDlq(String transferCommandsDlq) {
            this.transferCommandsDlq = transferCommandsDlq;
        }
    }

    public static class OutboxProperties {
        private boolean publisherEnabled = true;

        @Positive(message = "Outbox batch size must be positive")
        @Max(value = 500, message = "Outbox batch size must not exceed 500")
        private int batchSize = 50;

        @NotNull(message = "Outbox lease duration must not be null")
        @PositiveDuration(message = "Outbox lease duration must be positive")
        private Duration leaseDuration = Duration.ofSeconds(10);

        @NotNull(message = "Outbox poll interval must not be null")
        @PositiveDuration(message = "Outbox poll interval must be positive")
        private Duration pollInterval = Duration.ofMillis(500);

        @Positive(message = "Outbox max retries must be positive")
        @Max(value = 100, message = "Outbox max retries must not exceed 100")
        private int maxRetries = 5;

        public boolean isPublisherEnabled() {
            return publisherEnabled;
        }

        public void setPublisherEnabled(boolean publisherEnabled) {
            this.publisherEnabled = publisherEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public static class ProcessingProperties {
        private boolean manualEndpointEnabled = false;
        private boolean consumerEnabled = true;
        private boolean recoverySweepEnabled = true;

        @NotNull(message = "Processing lease duration must not be null")
        @PositiveDuration(message = "Processing lease duration must be positive")
        private Duration leaseDuration = Duration.ofSeconds(30);

        @Positive(message = "Processing max attempts must be positive")
        @Max(value = 50, message = "Processing max attempts must not exceed 50")
        private int maxAttempts = 5;

        @NotNull(message = "Processing initial backoff must not be null")
        @PositiveDuration(message = "Processing initial backoff must be positive")
        private Duration initialBackoff = Duration.ofSeconds(1);

        @NotNull(message = "Processing max backoff must not be null")
        @PositiveDuration(message = "Processing max backoff must be positive")
        private Duration maxBackoff = Duration.ofSeconds(60);

        @NotNull(message = "Recovery sweep interval must not be null")
        @PositiveDuration(message = "Recovery sweep interval must be positive")
        private Duration recoverySweepInterval = Duration.ofSeconds(15);

        @AssertTrue(message = "Processing max backoff must be greater than or equal to initial backoff")
        public boolean isBackoffValid() {
            if (initialBackoff == null || maxBackoff == null) {
                return true;
            }
            return maxBackoff.compareTo(initialBackoff) >= 0;
        }

        public boolean isManualEndpointEnabled() {
            return manualEndpointEnabled;
        }

        public void setManualEndpointEnabled(boolean manualEndpointEnabled) {
            this.manualEndpointEnabled = manualEndpointEnabled;
        }

        public boolean isConsumerEnabled() {
            return consumerEnabled;
        }

        public void setConsumerEnabled(boolean consumerEnabled) {
            this.consumerEnabled = consumerEnabled;
        }

        public boolean isRecoverySweepEnabled() {
            return recoverySweepEnabled;
        }

        public void setRecoverySweepEnabled(boolean recoverySweepEnabled) {
            this.recoverySweepEnabled = recoverySweepEnabled;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public Duration getRecoverySweepInterval() {
            return recoverySweepInterval;
        }

        public void setRecoverySweepInterval(Duration recoverySweepInterval) {
            this.recoverySweepInterval = recoverySweepInterval;
        }
    }

    public static class ReconciliationProperties {
        @NotNull(message = "Reconciliation interval must not be null")
        @PositiveDuration(message = "Reconciliation interval must be positive")
        private Duration interval = Duration.ofSeconds(60);

        @NotNull(message = "Stale processing threshold must not be null")
        @PositiveDuration(message = "Stale processing threshold must be positive")
        private Duration staleProcessingThreshold = Duration.ofSeconds(60);

        @NotNull(message = "Stale outbox threshold must not be null")
        @PositiveDuration(message = "Stale outbox threshold must be positive")
        private Duration staleOutboxThreshold = Duration.ofSeconds(30);

        @NotNull(message = "Manual trigger cooldown must not be null")
        @PositiveDuration(message = "Manual trigger cooldown must be positive")
        private Duration manualTriggerCooldown = Duration.ofSeconds(5);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getStaleProcessingThreshold() {
            return staleProcessingThreshold;
        }

        public void setStaleProcessingThreshold(Duration staleProcessingThreshold) {
            this.staleProcessingThreshold = staleProcessingThreshold;
        }

        public Duration getStaleOutboxThreshold() {
            return staleOutboxThreshold;
        }

        public void setStaleOutboxThreshold(Duration staleOutboxThreshold) {
            this.staleOutboxThreshold = staleOutboxThreshold;
        }

        public Duration getManualTriggerCooldown() {
            return manualTriggerCooldown;
        }

        public void setManualTriggerCooldown(Duration manualTriggerCooldown) {
            this.manualTriggerCooldown = manualTriggerCooldown;
        }
    }
}
