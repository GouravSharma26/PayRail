package com.payrail.outbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Value("${payrail.outbox.publisher-enabled:true}")
    private boolean publisherEnabled;

    @Value("${payrail.outbox.batch-size:50}")
    private int batchSize;

    @Value("${payrail.outbox.lease-duration:10s}")
    private Duration leaseDuration;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    @Value("${payrail.topics.transfer-commands-dlq:payrail.transfer-commands.dlq.v1}")
    private String dlqTopic;

    @Value("${payrail.outbox.max-retries:5}")
    private int maxRetries;

    private OutboxPublisherCrashInjector crashInjector = OutboxPublisherCrashInjector.noop();

    private com.payrail.config.PayRailMetrics metrics;

    public OutboxPublisherService(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(com.payrail.config.PayRailMetrics metrics) {
        this.metrics = metrics;
    }

    @Autowired(required = false)
    public void setCrashInjector(OutboxPublisherCrashInjector crashInjector) {
        this.crashInjector = crashInjector != null
                ? crashInjector
                : OutboxPublisherCrashInjector.noop();
    }

    @Scheduled(fixedDelayString = "${payrail.outbox.poll-interval:500ms}")
    public int pollAndPublish() {
        if (!publisherEnabled) {
            return 0;
        }
        return publishDueEvents();
    }

    /**
     * Claims and publishes due outbox events. Can be called directly by scheduler or tests.
     */
    public int publishDueEvents() {
        Instant now = Instant.now();
        UUID leaseToken = UUID.randomUUID();

        List<OutboxEventRecord> claimedEvents = outboxRepository.claimDueEvents(batchSize, leaseDuration, now, leaseToken);
        if (claimedEvents.isEmpty()) {
            return 0;
        }

        log.debug("Claimed {} outbox event(s) with leaseToken={}", claimedEvents.size(), leaseToken);
        int publishedCount = 0;

        for (OutboxEventRecord event : claimedEvents) {
            try {
                boolean success = publishSingleEvent(event, leaseToken);
                if (success) {
                    publishedCount++;
                }
            } catch (PublisherProcessHaltException e) {
                log.warn("Process halt simulated on event id={}; aborting further publication in this execution", event.eventId());
                break;
            }
        }
        return publishedCount;
    }

    private boolean isDlqEvent(String eventType) {
        return eventType != null && (eventType.equals("transfer.dlq") || eventType.endsWith(".dlq") || eventType.equalsIgnoreCase("dlq"));
    }

    private boolean publishSingleEvent(OutboxEventRecord event, UUID leaseToken) {
        String eventKey = (isDlqEvent(event.eventType()) && event.correlationId() != null)
                ? event.correlationId().toString()
                : (event.aggregateId() != null ? event.aggregateId().toString() : event.eventId().toString());
        try {
            // Test fault-injection point: simulate broker send failure
            crashInjector.beforeBrokerSend(event);

            String targetTopic = isDlqEvent(event.eventType()) ? dlqTopic : transferCommandsTopic;
            String messagePayload;

            if (isDlqEvent(event.eventType())) {
                messagePayload = event.payloadJson();
            } else {
                JsonNode payloadNode = objectMapper.readTree(event.payloadJson());
                EventEnvelope envelope = new EventEnvelope(
                        event.eventId(),
                        event.eventType(),
                        event.schemaVersion(),
                        event.createdAt(),
                        event.aggregateType(),
                        event.aggregateId(),
                        event.aggregateSequence(),
                        event.correlationId(),
                        payloadNode
                );
                messagePayload = objectMapper.writeValueAsString(envelope);
            }

            // Publish to Kafka outside DB transaction
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    targetTopic,
                    eventKey,
                    messagePayload
            );

            // Wait for broker acknowledgment with timeout
            SendResult<String, String> sendResult = future.get(5, TimeUnit.SECONDS);
            log.debug("Published outbox event id={} to topic={} partition={} offset={}",
                    event.eventId(),
                    sendResult.getRecordMetadata().topic(),
                    sendResult.getRecordMetadata().partition(),
                    sendResult.getRecordMetadata().offset());

            // Test fault-injection point: simulate crash right after broker ack before marking published in DB
            crashInjector.afterBrokerAckBeforeDbUpdate(event);

            // Mark published in DB only if lease token still matches
            boolean marked = outboxRepository.markPublished(event.eventId(), leaseToken, Instant.now());
            if (!marked) {
                log.warn("Outbox event id={} lease expired or pre-empted before marking published", event.eventId());
            } else if (metrics != null) {
                metrics.recordOutboxPublishSuccess();
            }
            return marked;

        } catch (PublisherProcessHaltException e) {
            // Re-throw process halt so DB state and active lease remain intact as at crash instant
            throw e;
        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} [aggregateId={}, attempts={}]: {}",
                    event.eventId(), event.aggregateId(), event.publishAttempts(), e.getMessage());

            if (event.publishAttempts() >= maxRetries) {
                log.warn("Outbox event id={} reached max retries ({}); marking as PUBLISH_RETRY_EXHAUSTED",
                        event.eventId(), maxRetries);
                outboxRepository.recordPublishExhausted(
                        event.eventId(),
                        leaseToken,
                        "PUBLISH_RETRY_EXHAUSTED",
                        "Max publish attempts reached (" + maxRetries + "): " + e.getMessage()
                );
                if (metrics != null) {
                    metrics.recordOutboxPublishExhausted();
                }
            } else {
                Instant nextAvailableAt = calculateBackoff(event.publishAttempts());
                outboxRepository.recordPublishFailure(
                        event.eventId(),
                        leaseToken,
                        "BROKER_PUBLISH_FAILED",
                        e.getMessage(),
                        nextAvailableAt
                );
                if (metrics != null) {
                    metrics.recordOutboxPublishFailure();
                }
            }
            return false;
        }
    }

    private Instant calculateBackoff(int attemptCount) {
        // Exponential backoff: min(60s, 1s * 2^(attempts-1)) + jitter
        long baseDelayMs = 1000L * (1L << Math.min(attemptCount - 1, 6)); // up to 64s
        long maxDelayMs = 60_000L;
        long delayMs = Math.min(baseDelayMs, maxDelayMs);
        long jitterMs = random.nextLong(Math.max(1, delayMs / 4));
        return Instant.now().plusMillis(delayMs + jitterMs);
    }
}
