package com.payrail.reconciliation.repository;

import com.payrail.reconciliation.domain.ReconciliationIssueRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationIssueRepository {

    void insert(ReconciliationIssueRecord issue);

    void insertAll(List<ReconciliationIssueRecord> issues);

    List<ReconciliationIssueRecord> findByRunId(UUID runId);

    List<ReconciliationIssueRecord> findByStatus(String status);
}
