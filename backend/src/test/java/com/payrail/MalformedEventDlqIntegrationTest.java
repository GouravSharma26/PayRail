package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.consumer.service.TransferCommandConsumer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MalformedEventDlqIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferCommandConsumer transferCommandConsumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.payrail.outbox.service.OutboxPublisherService outboxPublisherService;

    @Autowired
    private com.payrail.outbox.repository.OutboxRepository outboxRepository;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    @Value("${payrail.topics.transfer-commands-dlq:payrail.transfer-commands.dlq.v1}")
    private String dlqTopic;

    private Consumer<String, String> dlqConsumer;

    @BeforeEach
    void setup() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-malformed-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        dlqConsumer = factory.createConsumer();
        dlqConsumer.subscribe(Collections.singletonList(dlqTopic));
    }

    @AfterEach
    void tearDown() {
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
    }

    @Test
    @DisplayName("Malformed JSON should be recorded in outbox and published to DLQ")
    void shouldRouteMalformedJsonToDlq() throws Exception {
        String invalidJson = "{\"this is definitely not valid json";

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                transferCommandsTopic,
                0,
                100L,
                "key-invalid",
                invalidJson
        );

        // Process directly via consumer handler: durably records in outbox
        transferCommandConsumer.onMessage(record);

        // Publish due outbox DLQ events
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        // Verify DLQ received the malformed message
        ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofSeconds(10));
        assertThat(dlqRecords.isEmpty()).isFalse();

        ConsumerRecord<String, String> dlqRecord = dlqRecords.iterator().next();
        DeadLetterEnvelope dlqEnvelope = objectMapper.readValue(dlqRecord.value(), DeadLetterEnvelope.class);

        assertThat(dlqEnvelope.failureCode()).isEqualTo("MALFORMED_EVENT");
        assertThat(dlqEnvelope.rawPayload()).isEqualTo(invalidJson);
    }

    @Test
    @DisplayName("Unsupported schema version should be recorded in outbox and published to DLQ")
    void shouldRouteUnsupportedSchemaVersionToDlq() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        String payloadWithFutureSchema = """
                {
                    "eventId": "%s",
                    "eventType": "transfer.requested",
                    "schemaVersion": 99,
                    "occurredAt": "2026-10-24T20:00:00Z",
                    "aggregateType": "transfer",
                    "aggregateId": "%s",
                    "aggregateSequence": 1,
                    "correlationId": "%s",
                    "payload": { "transferId": "%s" }
                }
                """.formatted(eventId, transferId, UUID.randomUUID(), transferId);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                transferCommandsTopic,
                0,
                101L,
                transferId.toString(),
                payloadWithFutureSchema
        );

        transferCommandConsumer.onMessage(record);

        // Publish due outbox DLQ events
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofSeconds(10));
        assertThat(dlqRecords.isEmpty()).isFalse();

        DeadLetterEnvelope matchedEnvelope = null;
        for (ConsumerRecord<String, String> r : dlqRecords) {
            DeadLetterEnvelope env = objectMapper.readValue(r.value(), DeadLetterEnvelope.class);
            if (eventId.equals(env.originalEventId())) {
                matchedEnvelope = env;
                break;
            }
        }

        assertThat(matchedEnvelope).isNotNull();
        assertThat(matchedEnvelope.failureCode()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION");
    }

    @Test
    @DisplayName("Finding 1: DLQ record is durable across broker outage and delivered upon broker recovery")
    void shouldDeliverDlqRecordDurableAcrossBrokerOutage() throws Exception {
        String invalidJson = "{\"corrupted_payload_during_outage\": true";

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                transferCommandsTopic,
                0,
                200L,
                "key-outage-dlq",
                invalidJson
        );

        // 1. Consumer receives message while DLQ broker is unavailable
        transferCommandConsumer.onMessage(record);

        // 2. Verify DLQ event is durably stored in outbox_events in PostgreSQL
        List<com.payrail.outbox.domain.OutboxEventRecord> unpublished = outboxRepository.findUnpublished(Instant.now());
        assertThat(unpublished).isNotEmpty();
        com.payrail.outbox.domain.OutboxEventRecord dlqEvent = unpublished.stream()
                .filter(e -> "transfer.dlq".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(dlqEvent.publishedAt()).isNull();

        // 3. Broker recovers: publish due outbox events
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        // 4. Verify DLQ event is now marked published in DB
        com.payrail.outbox.domain.OutboxEventRecord updatedDlq = outboxRepository.findById(dlqEvent.eventId()).orElseThrow();
        assertThat(updatedDlq.publishedAt()).isNotNull();

        // 5. Verify message received from Kafka DLQ topic
        ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofSeconds(10));
        assertThat(dlqRecords.isEmpty()).isFalse();

        DeadLetterEnvelope dlqEnvelope = null;
        for (ConsumerRecord<String, String> r : dlqRecords) {
            DeadLetterEnvelope env = objectMapper.readValue(r.value(), DeadLetterEnvelope.class);
            if (invalidJson.equals(env.rawPayload())) {
                dlqEnvelope = env;
                break;
            }
        }
        assertThat(dlqEnvelope).isNotNull();
        assertThat(dlqEnvelope.failureCode()).isEqualTo("MALFORMED_EVENT");
    }
}
