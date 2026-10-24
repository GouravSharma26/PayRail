package com.payrail.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Centralized Micrometer metrics instrumentation for PayRail.
 * All metric names use bounded, low-cardinality tags to prevent label explosion.
 */
@Component
public class PayRailMetrics {

    // --- Transfer submission ---
    private final Counter transfersSubmitted;
    private final Counter transfersIdempotentReplay;

    // --- Transfer processing outcomes ---
    private final Counter transfersSucceeded;
    private final Counter transfersFailed;
    private final Counter transfersBusinessDeclined;

    // --- Processing timing ---
    private final Timer transferProcessingDuration;

    // --- Consumer events ---
    private final Counter consumerEventsReceived;
    private final Counter consumerEventsDeduplicated;
    private final Counter consumerEventsRetried;
    private final Counter consumerEventsDlq;
    private final Counter consumerEventsMalformed;

    // --- Outbox publisher ---
    private final Counter outboxPublishSuccess;
    private final Counter outboxPublishFailure;
    private final Counter outboxPublishExhausted;

    // --- Recovery sweeps ---
    private final Counter recoverySweepsRun;
    private final Counter recoveryCandidatesProcessed;
    private final Counter recoveryCandidatesFailed;

    // --- Reconciliation ---
    private final Counter reconciliationRunsCompleted;
    private final Counter reconciliationIssuesDetected;

    public PayRailMetrics(MeterRegistry registry) {
        // Transfer submission
        this.transfersSubmitted = Counter.builder("payrail.transfers.submitted")
                .description("Total transfer submission requests accepted")
                .register(registry);
        this.transfersIdempotentReplay = Counter.builder("payrail.transfers.idempotent_replay")
                .description("Idempotent replay responses returned")
                .register(registry);

        // Transfer processing outcomes
        this.transfersSucceeded = Counter.builder("payrail.transfers.succeeded")
                .description("Transfers that completed successfully with ledger postings")
                .register(registry);
        this.transfersFailed = Counter.builder("payrail.transfers.failed")
                .description("Transfers that failed with a terminal failure")
                .register(registry);
        this.transfersBusinessDeclined = Counter.builder("payrail.transfers.business_declined")
                .description("Transfers declined for business reasons (insufficient funds, inactive account, etc.)")
                .register(registry);

        // Processing duration
        this.transferProcessingDuration = Timer.builder("payrail.transfers.processing_duration")
                .description("Time spent processing a single transfer attempt")
                .register(registry);

        // Consumer events
        this.consumerEventsReceived = Counter.builder("payrail.consumer.events_received")
                .description("Total events received by the Kafka consumer")
                .register(registry);
        this.consumerEventsDeduplicated = Counter.builder("payrail.consumer.events_deduplicated")
                .description("Events skipped due to deduplication")
                .register(registry);
        this.consumerEventsRetried = Counter.builder("payrail.consumer.events_retried")
                .description("Events scheduled for retry after transient failure")
                .register(registry);
        this.consumerEventsDlq = Counter.builder("payrail.consumer.events_dlq")
                .description("Events sent to the dead-letter queue")
                .register(registry);
        this.consumerEventsMalformed = Counter.builder("payrail.consumer.events_malformed")
                .description("Events rejected due to malformed envelope or unsupported schema")
                .register(registry);

        // Outbox publisher
        this.outboxPublishSuccess = Counter.builder("payrail.outbox.publish_success")
                .description("Outbox events successfully published to Kafka")
                .register(registry);
        this.outboxPublishFailure = Counter.builder("payrail.outbox.publish_failure")
                .description("Outbox event publish failures (retriable)")
                .register(registry);
        this.outboxPublishExhausted = Counter.builder("payrail.outbox.publish_exhausted")
                .description("Outbox events that exhausted publish retries")
                .register(registry);

        // Recovery sweeps
        this.recoverySweepsRun = Counter.builder("payrail.recovery.sweeps_run")
                .description("Total recovery sweeps executed")
                .register(registry);
        this.recoveryCandidatesProcessed = Counter.builder("payrail.recovery.candidates_processed")
                .description("Stale candidates successfully recovered")
                .register(registry);
        this.recoveryCandidatesFailed = Counter.builder("payrail.recovery.candidates_failed")
                .description("Stale candidates that failed recovery")
                .register(registry);

        // Reconciliation
        this.reconciliationRunsCompleted = Counter.builder("payrail.reconciliation.runs_completed")
                .description("Reconciliation audit runs completed")
                .register(registry);
        this.reconciliationIssuesDetected = Counter.builder("payrail.reconciliation.issues_detected")
                .description("Total reconciliation issues detected across all runs")
                .register(registry);
    }

    // --- Transfer submission ---
    public void recordTransferSubmitted() {
        transfersSubmitted.increment();
    }

    public void recordIdempotentReplay() {
        transfersIdempotentReplay.increment();
    }

    // --- Transfer processing outcomes ---
    public void recordTransferSucceeded() {
        transfersSucceeded.increment();
    }

    public void recordTransferFailed() {
        transfersFailed.increment();
    }

    public void recordTransferBusinessDeclined() {
        transfersBusinessDeclined.increment();
    }

    public Timer.Sample startProcessingTimer() {
        return Timer.start();
    }

    public void stopProcessingTimer(Timer.Sample sample) {
        sample.stop(transferProcessingDuration);
    }

    // --- Consumer events ---
    public void recordConsumerEventReceived() {
        consumerEventsReceived.increment();
    }

    public void recordConsumerEventDeduplicated() {
        consumerEventsDeduplicated.increment();
    }

    public void recordConsumerEventRetried() {
        consumerEventsRetried.increment();
    }

    public void recordConsumerEventDlq() {
        consumerEventsDlq.increment();
    }

    public void recordConsumerEventMalformed() {
        consumerEventsMalformed.increment();
    }

    // --- Outbox publisher ---
    public void recordOutboxPublishSuccess() {
        outboxPublishSuccess.increment();
    }

    public void recordOutboxPublishFailure() {
        outboxPublishFailure.increment();
    }

    public void recordOutboxPublishExhausted() {
        outboxPublishExhausted.increment();
    }

    // --- Recovery sweeps ---
    public void recordRecoverySweepRun() {
        recoverySweepsRun.increment();
    }

    public void recordRecoveryCandidateProcessed() {
        recoveryCandidatesProcessed.increment();
    }

    public void recordRecoveryCandidateFailed() {
        recoveryCandidatesFailed.increment();
    }

    // --- Reconciliation ---
    public void recordReconciliationRunCompleted() {
        reconciliationRunsCompleted.increment();
    }

    public void recordReconciliationIssuesDetected(int count) {
        reconciliationIssuesDetected.increment(count);
    }
}
