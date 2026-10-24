package com.payrail.consumer.service;

import com.payrail.transfer.domain.TransferRecord;

import java.util.UUID;

/**
 * Test-controlled failure injection hook for asynchronous transfer processing tests.
 */
@FunctionalInterface
public interface AsyncProcessingFailureInjector {

    void beforePostingCommit(TransferRecord transfer, int attemptNumber);

    default void beforeClaim(UUID transferId) {}

    default void inRetryStateTransaction(UUID transferId, int attemptNumber) {}

    static AsyncProcessingFailureInjector noop() {
        return (transfer, attemptNumber) -> {};
    }
}
