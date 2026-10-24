package com.payrail.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.consumer.domain.ProcessedMessageRecord;
import com.payrail.consumer.repository.ProcessedMessagesRepository;
import com.payrail.outbox.domain.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Component
public class TransferCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferCommandConsumer.class);
    private static final String CONSUMER_NAME = "transfer-worker";

    private final AsyncTransferProcessor transferProcessor;
    private final DeadLetterPublisher deadLetterPublisher;
    private final ProcessedMessagesRepository processedMessagesRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private com.payrail.config.PayRailMetrics metrics;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    public TransferCommandConsumer(
            AsyncTransferProcessor transferProcessor,
            DeadLetterPublisher deadLetterPublisher,
            ProcessedMessagesRepository processedMessagesRepository,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.transferProcessor = transferProcessor;
        this.deadLetterPublisher = deadLetterPublisher;
        this.processedMessagesRepository = processedMessagesRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(com.payrail.config.PayRailMetrics metrics) {
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${payrail.topics.transfer-commands:payrail.transfer-commands.v1}",
            groupId = "${spring.kafka.consumer.group-id:payrail-transfer-workers}",
            autoStartup = "${payrail.processing.consumer-enabled:true}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        if (metrics != null) {
            metrics.recordConsumerEventReceived();
        }
        String rawMessage = record.value();
        log.debug("Received message from topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(rawMessage, EventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to parse event envelope from topic {} partition {} offset {}: {}",
                    record.topic(), record.partition(), record.offset(), e.getMessage());

            if (metrics != null) {
                metrics.recordConsumerEventMalformed();
                metrics.recordConsumerEventDlq();
            }

            transactionTemplate.executeWithoutResult(status -> {
                deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                        record.topic(),
                        null,
                        null,
                        "MALFORMED_EVENT",
                        "Failed to deserialize event envelope: " + e.getMessage(),
                        0,
                        Instant.now(),
                        rawMessage
                ));
            });
            return;
        }

        if (!envelope.isValid()) {
            log.warn("Invalid event envelope received with missing fields: {}", envelope);
            if (metrics != null) {
                metrics.recordConsumerEventMalformed();
                metrics.recordConsumerEventDlq();
            }
            transactionTemplate.executeWithoutResult(status -> {
                deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                        record.topic(),
                        envelope.eventId(),
                        envelope.aggregateId(),
                        "INVALID_ENVELOPE",
                        "Envelope fields are incomplete or invalid",
                        0,
                        Instant.now(),
                        rawMessage
                ));
                if (envelope.eventId() != null) {
                    processedMessagesRepository.insert(new ProcessedMessageRecord(
                            CONSUMER_NAME,
                            envelope.eventId(),
                            Instant.now(),
                            "INVALID_ENVELOPE"
                    ));
                }
            });
            return;
        }

        if (envelope.schemaVersion() != EventEnvelope.CURRENT_SCHEMA_VERSION) {
            log.warn("Unsupported event schema version {} (expected {}) on event id={}",
                    envelope.schemaVersion(), EventEnvelope.CURRENT_SCHEMA_VERSION, envelope.eventId());
            if (metrics != null) {
                metrics.recordConsumerEventMalformed();
                metrics.recordConsumerEventDlq();
            }
            transactionTemplate.executeWithoutResult(status -> {
                deadLetterPublisher.recordDeadLetter(new DeadLetterEnvelope(
                        record.topic(),
                        envelope.eventId(),
                        envelope.aggregateId(),
                        "UNSUPPORTED_SCHEMA_VERSION",
                        "Unsupported schema version: " + envelope.schemaVersion(),
                        0,
                        Instant.now(),
                        rawMessage
                ));
                if (envelope.eventId() != null) {
                    processedMessagesRepository.insert(new ProcessedMessageRecord(
                            CONSUMER_NAME,
                            envelope.eventId(),
                            Instant.now(),
                            "SCHEMA_UNSUPPORTED"
                    ));
                }
            });
            return;
        }

        try {
            transferProcessor.processTransferEvent(envelope);
        } catch (Exception e) {
            log.error("Unhandled exception processing event id={} [aggregateId={}]: {}",
                    envelope.eventId(), envelope.aggregateId(), e.getMessage(), e);
            throw e;
        }
    }
}
