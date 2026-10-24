package com.payrail.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public DeadLetterPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    private volatile DeadLetterFailureInjector failureInjector;

    public void setFailureInjector(DeadLetterFailureInjector failureInjector) {
        this.failureInjector = failureInjector;
    }

    /**
     * Durably records a dead-letter event into the outbox within the caller's active database transaction.
     * Uses an independent aggregate identity for each DLQ event to ensure concurrency safety and prevent
     * aggregate sequence collisions, while preserving transfer and event IDs in the payload and correlationId.
     * The leased outbox publisher will publish it asynchronously to payrail.transfer-commands.dlq.v1
     * outside any DB transaction, with broker acknowledgment and retry tracking.
     */
    public UUID recordDeadLetter(DeadLetterEnvelope dlqMessage) {
        if (failureInjector != null) {
            failureInjector.beforeRecordDeadLetter(dlqMessage);
        }
        try {
            UUID eventId = UUID.randomUUID();
            String aggregateType = "dlq";
            UUID aggregateId = eventId;
            long aggregateSequence = 1L;

            String payloadJson = objectMapper.writeValueAsString(dlqMessage);
            UUID correlationId = dlqMessage.transferId() != null
                    ? dlqMessage.transferId()
                    : (dlqMessage.originalEventId() != null ? dlqMessage.originalEventId() : eventId);
            Instant now = dlqMessage.failedAt() != null ? dlqMessage.failedAt() : Instant.now();

            OutboxEventRecord dlqOutboxEvent = new OutboxEventRecord(
                    eventId,
                    aggregateType,
                    aggregateId,
                    aggregateSequence,
                    "transfer.dlq",
                    1,
                    payloadJson,
                    correlationId,
                    now,
                    now,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null
            );

            outboxRepository.insert(dlqOutboxEvent);
            log.warn("Recorded durable DLQ outbox event id={} for transferId={} failureCode={}",
                    eventId, dlqMessage.transferId(), dlqMessage.failureCode());
            return eventId;
        } catch (Exception e) {
            log.error("Failed to record durable dead-letter outbox event: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to record durable dead-letter event", e);
        }
    }

    /**
     * Enqueues the DLQ message durably to outbox. Does not perform direct Kafka publishing
     * inside database transactions.
     */
    public void publish(DeadLetterEnvelope dlqMessage) {
        recordDeadLetter(dlqMessage);
    }
}
