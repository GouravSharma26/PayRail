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
import java.util.List;
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

    @Autowired
    private com.payrail.ledger.service.ReconciliationService reconciliationService;

    @Autowired
    private com.payrail.reconciliation.repository.ReconciliationIssueRepository issueRepository;

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
        outboxPublisherService.setCrashInjector(null);
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

    @Test
    @DisplayName("Finding 2: Outbox retries stop at configured limit, mark PUBLISH_RETRY_EXHAUSTED, exclude from automatic claims, and flag in reconciliation")
    void shouldExhaustRetriesAtConfiguredLimitWhenBrokerFailsPermanently() throws Exception {
        String idempotencyKey = "key-exhaust-pub-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "50.00", "INR");

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

        OutboxEventRecord initialOutbox = outboxRepository.findUnpublished(java.time.Instant.now())
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();

        // 1. Configure crash injector to simulate permanent broker outage on send
        outboxPublisherService.setCrashInjector(new com.payrail.outbox.service.OutboxPublisherCrashInjector() {
            @Override
            public void afterBrokerAckBeforeDbUpdate(OutboxEventRecord event) {}

            @Override
            public void beforeBrokerSend(OutboxEventRecord event) {
                if (event.eventId().equals(initialOutbox.eventId())) {
                    throw new RuntimeException("Simulated persistent Kafka broker connection failure");
                }
            }
        });

        // 2. Execute attempts 1 through 5 (configured maxRetries is 5)
        for (int attempt = 1; attempt <= 5; attempt++) {
            // Make event immediately available for each attempt
            jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 second', lease_until = NULL WHERE event_id = ?", initialOutbox.eventId());
            outboxPublisherService.publishDueEvents();
        }

        // 3. Inspect event state at exact limit (5 attempts)
        OutboxEventRecord exhaustedEvent = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(exhaustedEvent.publishedAt()).isNull();
        assertThat(exhaustedEvent.publishAttempts()).isEqualTo(5);
        assertThat(exhaustedEvent.lastErrorCode()).isEqualTo("PUBLISH_RETRY_EXHAUSTED");
        assertThat(exhaustedEvent.lastErrorMessage()).contains("Max publish attempts reached (5)");
        assertThat(exhaustedEvent.leaseToken()).isNull();
        assertThat(exhaustedEvent.leaseUntil()).isNull();

        // 4. Verify attempts stop: automatic retry poller skips exhausted event even if available_at is in the past
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 second' WHERE event_id = ?", initialOutbox.eventId());
        int attempt6Count = outboxPublisherService.publishDueEvents();
        assertThat(attempt6Count).isEqualTo(0);

        OutboxEventRecord stillExhausted = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(stillExhausted.publishAttempts()).isEqualTo(5); // Did not increment to 6

        // 5. Verify visible to reconciliation audit
        com.payrail.reconciliation.domain.ReconciliationRunRecord run = reconciliationService.runAuditAndPersist("TEST_AUDIT");
        assertThat(run.issueCount()).isGreaterThanOrEqualTo(1);

        List<com.payrail.reconciliation.domain.ReconciliationIssueRecord> issues = issueRepository.findByRunId(run.id());
        com.payrail.reconciliation.domain.ReconciliationIssueRecord matchedIssue = issues.stream()
                .filter(i -> "OUTBOX_PUBLISH_EXHAUSTED".equals(i.issueType()) && initialOutbox.eventId().equals(i.outboxEventId()))
                .findFirst()
                .orElse(null);

        assertThat(matchedIssue).isNotNull();
        assertThat(matchedIssue.severity()).isEqualTo("CRITICAL");
        assertThat(matchedIssue.status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("Finding 2: Recovers successfully before exhaustion when broker recovers on attempt 3")
    void shouldRecoverBeforeExhaustionWhenBrokerRecovers() throws Exception {
        String idempotencyKey = "key-recover-pub-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "60.00", "INR");

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

        OutboxEventRecord initialOutbox = outboxRepository.findUnpublished(java.time.Instant.now())
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();

        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Fail first 2 attempts, then recover on attempt 3
        outboxPublisherService.setCrashInjector(new com.payrail.outbox.service.OutboxPublisherCrashInjector() {
            @Override
            public void afterBrokerAckBeforeDbUpdate(OutboxEventRecord event) {}

            @Override
            public void beforeBrokerSend(OutboxEventRecord event) {
                if (event.eventId().equals(initialOutbox.eventId()) && failureCount.getAndIncrement() < 2) {
                    throw new RuntimeException("Transient broker outage on attempt " + failureCount.get());
                }
            }
        });

        // Attempt 1: Fails
        outboxPublisherService.publishDueEvents();
        OutboxEventRecord after1 = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(after1.publishedAt()).isNull();
        assertThat(after1.publishAttempts()).isEqualTo(1);
        assertThat(after1.lastErrorCode()).isEqualTo("BROKER_PUBLISH_FAILED");

        // Attempt 2: Fails
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 second' WHERE event_id = ?", initialOutbox.eventId());
        outboxPublisherService.publishDueEvents();
        OutboxEventRecord after2 = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(after2.publishedAt()).isNull();
        assertThat(after2.publishAttempts()).isEqualTo(2);

        // Attempt 3: Broker has recovered; succeeds!
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 second' WHERE event_id = ?", initialOutbox.eventId());
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        OutboxEventRecord after3 = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(after3.publishedAt()).isNotNull();
        assertThat(after3.publishAttempts()).isEqualTo(3);
        assertThat(after3.lastErrorCode()).isNull();
    }

    @Test
    @DisplayName("Finding 2: Operator replay mechanism resets exhausted outbox event and allows successful publication")
    void shouldAllowOperatorToReplayExhaustedEventAfterBrokerRecovers() throws Exception {
        String idempotencyKey = "key-replay-pub-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "70.00", "INR");

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

        OutboxEventRecord initialOutbox = outboxRepository.findUnpublished(java.time.Instant.now())
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();

        // 1. Simulate exhausted state directly in DB
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET publish_attempts = 5,
                    last_error_code = 'PUBLISH_RETRY_EXHAUSTED',
                    last_error_message = 'Max publish attempts reached (5)',
                    lease_token = NULL,
                    lease_until = NULL
                WHERE event_id = ?
                """, initialOutbox.eventId());

        // Verify it is excluded from claim
        int countBeforeReplay = outboxPublisherService.publishDueEvents();
        assertThat(countBeforeReplay).isEqualTo(0);

        // 2. Operator performs controlled replay reset
        boolean resetSuccess = outboxRepository.resetForReplay(initialOutbox.eventId(), java.time.Instant.now());
        assertThat(resetSuccess).isTrue();

        OutboxEventRecord replayedRecord = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(replayedRecord.publishAttempts()).isEqualTo(0);
        assertThat(replayedRecord.lastErrorCode()).isNull();
        assertThat(replayedRecord.lastErrorMessage()).isNull();
        assertThat(replayedRecord.publishedAt()).isNull();

        // 3. Next publisher run successfully claims and publishes the event to broker
        int publishedCount = outboxPublisherService.publishDueEvents();
        assertThat(publishedCount).isGreaterThanOrEqualTo(1);

        OutboxEventRecord finalPublished = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(finalPublished.publishedAt()).isNotNull();
        assertThat(finalPublished.publishAttempts()).isEqualTo(1);
    }
}
