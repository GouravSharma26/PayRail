package com.payrail.consumer.service;

import com.payrail.consumer.exception.DurableRecoveryUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Container-level error recoverer that enforces message preservation when durable recovery
 * (e.g. DLQ outbox record or transaction-state persistence) could not be committed.
 * Refuses acknowledgment and throws an exception, causing the listener container
 * to seek back to the failed offset rather than silently skipping the input message.
 */
public class DurableRecoveryEnforcingRecordRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DurableRecoveryEnforcingRecordRecoverer.class);

    private final long backoffDelayMs;

    public DurableRecoveryEnforcingRecordRecoverer() {
        this(250L);
    }

    public DurableRecoveryEnforcingRecordRecoverer(long backoffDelayMs) {
        this.backoffDelayMs = backoffDelayMs;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Container retries exhausted for topic={} partition={} offset={} key={}; " +
                        "durable recovery outcome could not be persisted. Refusing acknowledgment to preserve record for redelivery.",
                record.topic(), record.partition(), record.offset(), record.key(), exception);

        // Enforce bounded delay between retry cycles to avoid hot spinning when seeked back by container
        if (backoffDelayMs > 0) {
            try {
                Thread.sleep(backoffDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        throw new DurableRecoveryUnavailableException(
                "Durable recovery outcome could not be persisted for topic=" + record.topic()
                        + " partition=" + record.partition() + " offset=" + record.offset()
                        + "; refusing acknowledgment to prevent message loss",
                exception
        );
    }
}
