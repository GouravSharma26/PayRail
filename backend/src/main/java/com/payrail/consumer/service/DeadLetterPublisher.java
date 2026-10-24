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

    /**
     * Durably records a dead-letter event into the outbox within the caller's active database transaction.
     * The leased outbox publisher will publish it asynchronously to payrail.transfer-commands.dlq.v1
     * outside any DB transaction, with broker acknowledgment and retry tracking.
     */
    public UUID recordDeadLetter(DeadLetterEnvelope dlqMessage) {
        try {
            String aggregateType = dlqMessage.transferId() != null ? "transfer" : "dlq";
            UUID aggregateId = dlqMessage.transferId() != null
                    ? dlqMessage.transferId()
                    : (dlqMessage.originalEventId() != null ? dlqMessage.originalEventId() : UUID.randomUUID());

            long aggregateSequence = outboxRepository.getNextAggregateSequence(aggregateType, aggregateId);
            String payloadJson = objectMapper.writeValueAsString(dlqMessage);
            UUID eventId = UUID.randomUUID();
            UUID correlationId = dlqMessage.originalEventId() != null ? dlqMessage.originalEventId() : aggregateId;
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
            log.warn("Recorded durable DLQ outbox event id={} for aggregateId={} failureCode={}",
                    eventId, aggregateId, dlqMessage.failureCode());
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
