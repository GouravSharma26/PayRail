package com.payrail.idempotency.repository;

import com.payrail.idempotency.domain.IdempotencyRecord;

import java.util.Optional;

public interface IdempotencyRepository {

    void insert(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByKey(String idempotencyKey);
}
