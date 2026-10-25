package com.payrail.reconciliation.service;

import com.payrail.reconciliation.domain.ReconciliationIssueRecord;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;

import java.util.List;

/**
 * Test hook interface for injecting simulated failures or pauses during reconciliation execution.
 */
@FunctionalInterface
public interface ReconciliationFailureInjector {

    /**
     * Invoked immediately after the reconciliation_run record has been inserted into the database,
     * but before issue records are inserted or the transaction commits.
     *
     * @param runRecord the inserted reconciliation run record
     * @param issues    the list of issues pending insertion
     */
    void afterRunPersisted(ReconciliationRunRecord runRecord, List<ReconciliationIssueRecord> issues);

    static ReconciliationFailureInjector noop() {
        return (run, issues) -> {};
    }
}
