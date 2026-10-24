package com.payrail.outbox.service;

import com.payrail.outbox.domain.OutboxEventRecord;

/**
 * Test-controlled fault injection hook for outbox publisher crash simulations.
 */
@FunctionalInterface
public interface OutboxPublisherCrashInjector {

    void afterBrokerAckBeforeDbUpdate(OutboxEventRecord event);

    default void beforeBrokerSend(OutboxEventRecord event) {}

    static OutboxPublisherCrashInjector noop() {
        return event -> {};
    }
}
