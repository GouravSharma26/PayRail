package com.payrail.consumer.service;

import com.payrail.transfer.domain.TransferRecord;

/**
 * Test-controlled failure injection hook for asynchronous transfer processing tests.
 */
@FunctionalInterface
public interface AsyncProcessingFailureInjector {

    void beforePostingCommit(TransferRecord transfer, int attemptNumber);

    static AsyncProcessingFailureInjector noop() {
        return (transfer, attemptNumber) -> {};
    }
}
