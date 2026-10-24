package com.payrail.reconciliation.repository;

import com.payrail.reconciliation.domain.ReconciliationRunRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunRepository {

    void insert(ReconciliationRunRecord run);

    Optional<ReconciliationRunRecord> findById(UUID id);

    List<ReconciliationRunRecord> findRecent(int limit);
}
