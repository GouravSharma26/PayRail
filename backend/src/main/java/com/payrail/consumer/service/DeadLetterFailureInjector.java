package com.payrail.consumer.service;

import com.payrail.consumer.domain.DeadLetterEnvelope;

/**
 * Test hook to simulate transient database failures during dead-letter outbox persistence.
 */
@FunctionalInterface
public interface DeadLetterFailureInjector {

    void beforeRecordDeadLetter(DeadLetterEnvelope dlqMessage);

    static DeadLetterFailureInjector noop() {
        return dlqMessage -> {};
    }
}
