package com.payrail.consumer.repository;

import com.payrail.consumer.domain.ProcessedMessageRecord;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedMessagesRepository {

    void insert(ProcessedMessageRecord record);

    boolean exists(String consumerName, UUID eventId);

    Optional<ProcessedMessageRecord> find(String consumerName, UUID eventId);
}
