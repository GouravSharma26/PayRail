package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.repository.AccountRepository;
import com.payrail.consumer.service.AsyncTransferProcessor;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.outbox.service.OutboxPublisherService;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.repository.TransferRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherCrashRecoveryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private AsyncTransferProcessor asyncTransferProcessor;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

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
                new CreateAccountRequest("Crash Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Crash Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-crash-verifier-" + UUID.randomUUID());
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
    @DisplayName("Publisher crash after broker ack is safe: duplicate publication is absorbed by consumer")
    void shouldSafelyHandlePublisherCrashAfterBrokerAck() throws Exception {
        String idempotencyKey = "key-crash-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "300.00", "INR");

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

        // 1. Configure crash injector to simulate publisher crashing right after Kafka ACK, before DB update
        AtomicBoolean crashTriggered = new AtomicBoolean(false);
        outboxPublisherService.setCrashInjector(event -> {
            if (crashTriggered.compareAndSet(false, true)) {
                throw new RuntimeException("Simulated JVM crash after broker ack before marking published");
            }
        });

        // 2. Publish attempt 1: publishes to broker, then crashes before DB update
        outboxPublisherService.publishDueEvents();
        assertThat(crashTriggered.get()).isTrue();

        // Verify outbox row is NOT marked published in DB
        OutboxEventRecord pendingOutbox = outboxRepository.findUnpublished(java.time.Instant.now())
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();
        assertThat(pendingOutbox.publishedAt()).isNull();

        // 3. Clear crash injector and reset available_at to the past to guarantee due status
        outboxPublisherService.setCrashInjector(null);
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 minute', lease_token = NULL, lease_until = NULL WHERE event_id = ?", pendingOutbox.eventId());

        // 4. Publish attempt 2: republishes the same event and successfully marks published in DB
        int publishedCount = outboxPublisherService.publishDueEvents();
        assertThat(publishedCount).isGreaterThanOrEqualTo(1);

        OutboxEventRecord completedOutbox = outboxRepository.findById(pendingOutbox.eventId()).orElseThrow();
        assertThat(completedOutbox.publishedAt()).isNotNull();

        // 5. Verify broker received both the first and second publication (duplicate messages in topic)
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        List<ConsumerRecord<String, String>> receivedForTransfer = records.records(new org.apache.kafka.common.TopicPartition(transferCommandsTopic, 0))
                .stream()
                .filter(r -> transferId.toString().equals(r.key()))
                .toList();

        // If partitions > 1, scan all records
        long totalRecordsForTransfer = 0;
        for (ConsumerRecord<String, String> r : records) {
            if (transferId.toString().equals(r.key())) {
                totalRecordsForTransfer++;
                EventEnvelope envelope = objectMapper.readValue(r.value(), EventEnvelope.class);
                // Dispatch each received message to the consumer processor
                asyncTransferProcessor.processTransferEvent(envelope);
            }
        }
        assertThat(totalRecordsForTransfer).isGreaterThanOrEqualTo(2);

        // 6. Verify consumer safely absorbed the duplicate delivery:
        // - Transfer is SUCCEEDED
        // - Exactly 1 debit and 1 credit ledger entries exist
        // - Balances are exactly 700.00 and 800.00
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
    }

    @Test
    @DisplayName("Test fidelity: Process crash after broker ack leaves lease intact until expiry; republished and deduplicated")
    void shouldPreserveLeaseAndRecoverAfterExpiryOnPublisherCrash() throws Exception {
        String idempotencyKey = "key-crash-lease-" + UUID.randomUUID();
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

        // 1. Configure crash injector to simulate JVM halt after broker ack before DB update
        AtomicBoolean crashed = new AtomicBoolean(false);
        outboxPublisherService.setCrashInjector(new com.payrail.outbox.service.OutboxPublisherCrashInjector() {
            @Override
            public void afterBrokerAckBeforeDbUpdate(OutboxEventRecord event) {
                if (crashed.compareAndSet(false, true)) {
                    throw new com.payrail.outbox.service.PublisherProcessHaltException("Simulated JVM crash after broker ack before marking published in DB");
                }
            }
        });

        // 2. Publisher A claims event and publishes, then crashes
        outboxPublisherService.publishDueEvents();
        assertThat(crashed.get()).isTrue();

        // 3. Verify event remains UNPUBLISHED and its LEASE IS INTACT in DB
        OutboxEventRecord unpub = outboxRepository.findById(
                outboxRepository.findUnpublished(java.time.Instant.now())
                        .stream()
                        .filter(e -> e.aggregateId().equals(transferId))
                        .findFirst()
                        .orElseThrow()
                        .eventId()
        ).orElseThrow();

        assertThat(unpub.publishedAt()).isNull();
        assertThat(unpub.leaseToken()).isNotNull();
        assertThat(unpub.leaseUntil()).isNotNull();
        assertThat(unpub.leaseUntil()).isAfter(java.time.Instant.now().minusSeconds(1));

        // While lease is intact and active, another publisher cannot claim it
        int immediateAttempt = outboxPublisherService.publishDueEvents();
        assertThat(immediateAttempt).isEqualTo(0);

        // 4. Simulate lease expiry by expiring lease_until to the past
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 minute', lease_until = now() - INTERVAL '10 seconds' WHERE event_id = ?", unpub.eventId());

        // 5. Publisher B recovers and publishes due events
        outboxPublisherService.setCrashInjector(null);
        int publishedCount = outboxPublisherService.publishDueEvents();
        assertThat(publishedCount).isGreaterThanOrEqualTo(1);

        OutboxEventRecord markedPub = outboxRepository.findById(unpub.eventId()).orElseThrow();
        assertThat(markedPub.publishedAt()).isNotNull();

        // 6. Consumer receives duplicate Kafka deliveries and deduplicates
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        long count = 0;
        for (ConsumerRecord<String, String> r : records) {
            if (transferId.toString().equals(r.key())) {
                count++;
                EventEnvelope envelope = objectMapper.readValue(r.value(), EventEnvelope.class);
                asyncTransferProcessor.processTransferEvent(envelope);
            }
        }
        assertThat(count).isGreaterThanOrEqualTo(2);

        // 7. Invariants: Exactly one debit and one credit, terminal SUCCEEDED
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("850.00")); // 1000 - 150
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("650.00")); // 500 + 150
    }
}
