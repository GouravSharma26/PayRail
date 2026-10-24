package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.outbox.service.OutboxPublisherService;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    private Consumer<String, String> testConsumer;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Publisher Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Publisher Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-publisher-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        testConsumer = factory.createConsumer();
        testConsumer.subscribe(Collections.singletonList(transferCommandsTopic));
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    @Test
    @DisplayName("Should create outbox event at submission and publish to broker with acknowledgment setting published_at")
    void shouldPublishOutboxEventWithBrokerAcknowledgment() throws Exception {
        String idempotencyKey = "key-outbox-pub-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "150.00", "INR");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());

        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TransferResponse.class
        );

        UUID transferId = response.getBody().id();

        // 1. Verify Outbox event created and unpublished
        OutboxEventRecord initialOutbox = outboxRepository.findUnpublished(java.time.Instant.now())
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();

        assertThat(initialOutbox.publishedAt()).isNull();
        assertThat(initialOutbox.leaseToken()).isNull();
        assertThat(initialOutbox.publishAttempts()).isEqualTo(0);
        assertThat(initialOutbox.eventType()).isEqualTo("transfer.requested");
        assertThat(initialOutbox.schemaVersion()).isEqualTo(1);

        // 2. Run leased outbox publisher
        int publishedCount = outboxPublisherService.publishDueEvents();
        assertThat(publishedCount).isGreaterThanOrEqualTo(1);

        // 3. Verify Outbox row updated with published_at and lease cleared
        OutboxEventRecord updatedOutbox = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(updatedOutbox.publishedAt()).isNotNull();
        assertThat(updatedOutbox.leaseToken()).isNull();
        assertThat(updatedOutbox.leaseUntil()).isNull();
        assertThat(updatedOutbox.publishAttempts()).isEqualTo(1);

        // 4. Verify Kafka broker received message with transferId as key and versioned JSON envelope
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records.isEmpty()).isFalse();

        ConsumerRecord<String, String> receivedRecord = null;
        for (ConsumerRecord<String, String> record : records) {
            if (transferId.toString().equals(record.key())) {
                receivedRecord = record;
                break;
            }
        }

        assertThat(receivedRecord).isNotNull();
        assertThat(receivedRecord.key()).isEqualTo(transferId.toString());

        EventEnvelope envelope = objectMapper.readValue(receivedRecord.value(), EventEnvelope.class);
        assertThat(envelope.eventId()).isEqualTo(initialOutbox.eventId());
        assertThat(envelope.eventType()).isEqualTo("transfer.requested");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.aggregateType()).isEqualTo("transfer");
        assertThat(envelope.aggregateId()).isEqualTo(transferId);
        assertThat(envelope.payload().get("transferId").asText()).isEqualTo(transferId.toString());
    }
}
