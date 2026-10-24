package com.payrail.outbox.repository;

import com.payrail.outbox.domain.OutboxEventRecord;

import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository {

    void insert(OutboxEventRecord event);

    Optional<OutboxEventRecord> findById(UUID eventId);
}
