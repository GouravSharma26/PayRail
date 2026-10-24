package com.payrail.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers gauges for outbox and processing backlog that are periodically refreshed.
 * Gauge values are updated lazily by the metrics scraper (Prometheus pull model).
 */
@Component
public class BacklogGauges {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong outboxPending = new AtomicLong(0);
    private final AtomicLong outboxFailed = new AtomicLong(0);
    private final AtomicLong outboxExhausted = new AtomicLong(0);
    private final AtomicLong transfersProcessing = new AtomicLong(0);
    private final AtomicLong transfersPending = new AtomicLong(0);

    public BacklogGauges(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;

        Gauge.builder("payrail.outbox.pending_count", outboxPending, AtomicLong::doubleValue)
                .description("Outbox events awaiting first publish attempt")
                .register(registry);

        Gauge.builder("payrail.outbox.failed_count", outboxFailed, AtomicLong::doubleValue)
                .description("Outbox events that failed and are awaiting retry")
                .register(registry);

        Gauge.builder("payrail.outbox.exhausted_count", outboxExhausted, AtomicLong::doubleValue)
                .description("Outbox events that exhausted all publish retries")
                .register(registry);

        Gauge.builder("payrail.transfers.processing_count", transfersProcessing, AtomicLong::doubleValue)
                .description("Transfers currently in PROCESSING state")
                .register(registry);

        Gauge.builder("payrail.transfers.pending_count", transfersPending, AtomicLong::doubleValue)
                .description("Transfers currently in PENDING state")
                .register(registry);
    }

    /**
     * Called by a scheduled task to refresh all backlog gauge values.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${payrail.metrics.gauge-refresh-interval:10000}")
    public void refreshGauges() {
        try {
            Long pending = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND last_error_code IS NULL", Long.class);
            outboxPending.set(pending != null ? pending : 0);

            Long failed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND last_error_code IS NOT NULL AND last_error_code <> 'PUBLISH_RETRY_EXHAUSTED'", Long.class);
            outboxFailed.set(failed != null ? failed : 0);

            Long exhausted = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND last_error_code = 'PUBLISH_RETRY_EXHAUSTED'", Long.class);
            outboxExhausted.set(exhausted != null ? exhausted : 0);

            Long processing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE status = 'PROCESSING'", Long.class);
            transfersProcessing.set(processing != null ? processing : 0);

            Long pendingTransfers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfers WHERE status = 'PENDING'", Long.class);
            transfersPending.set(pendingTransfers != null ? pendingTransfers : 0);
        } catch (Exception e) {
            // Gauge refresh is best-effort; do not disrupt the application
            org.slf4j.LoggerFactory.getLogger(BacklogGauges.class)
                    .warn("Failed to refresh backlog gauges: {}", e.getMessage());
        }
    }

    // Accessors for testing
    public long getOutboxPending() { return outboxPending.get(); }
    public long getOutboxFailed() { return outboxFailed.get(); }
    public long getOutboxExhausted() { return outboxExhausted.get(); }
    public long getTransfersProcessing() { return transfersProcessing.get(); }
    public long getTransfersPending() { return transfersPending.get(); }
}
