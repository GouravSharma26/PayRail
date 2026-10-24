package com.payrail.ledger.service;

import com.payrail.transfer.domain.TransferRecord;

/**
 * Hook for test-controlled fault injection to verify atomic rollback behavior.
 * Default runtime implementation is a no-op.
 */
@FunctionalInterface
public interface PostingFailureInjector {

    void beforeLedgerWrite(TransferRecord transfer);

    static PostingFailureInjector noop() {
        return transfer -> {};
    }
}
