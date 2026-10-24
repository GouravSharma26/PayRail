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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerOutageRecoveryIntegrationTest extends BaseIntegrationTest {

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
                new CreateAccountRequest("Outage Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Outage Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-outage-verifier-" + UUID.randomUUID());
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
    @DisplayName("API transfer persists even during broker outage; outbox publishes and transfer completes when broker returns")
    void shouldPersistTransferDuringBrokerOutageAndRecoverWhenBrokerReturns() throws Exception {
        String idempotencyKey = "key-outage-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "250.00", "INR");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());

        // 1. Submit transfer via REST API
        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TransferResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID transferId = response.getBody().id();

        // 2. Assert transfer row is durable with status PENDING and outbox row exists unpublished
        TransferRecord initialTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(initialTransfer.status()).isEqualTo(TransferStatus.PENDING);

        OutboxEventRecord initialOutbox = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();
        assertThat(initialOutbox.publishedAt()).isNull();

        // 3. Simulate broker outage during publishing: publisher attempts to publish but broker connection fails
        AtomicBoolean outageActive = new AtomicBoolean(true);
        outboxPublisherService.setCrashInjector(event -> {
            if (outageActive.get()) {
                throw new RuntimeException("Simulated Kafka cluster connection timeout (broker unavailable)");
            }
        });

        int publishedDuringOutage = outboxPublisherService.publishDueEvents();
        assertThat(publishedDuringOutage).isEqualTo(0);

        // Verify outbox row recorded failure and remains unpublished in PostgreSQL
        OutboxEventRecord failedOutbox = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(failedOutbox.publishedAt()).isNull();
        assertThat(failedOutbox.lastErrorCode()).isEqualTo("BROKER_PUBLISH_FAILED");
        assertThat(failedOutbox.publishAttempts()).isGreaterThanOrEqualTo(1);

        // Verify transfer still remains safely PENDING in DB
        TransferRecord transferDuringOutage = transferRepository.findById(transferId).orElseThrow();
        assertThat(transferDuringOutage.status()).isEqualTo(TransferStatus.PENDING);

        // 4. Broker recovers: disable outage simulation and reset available_at
        outageActive.set(false);
        outboxPublisherService.setCrashInjector(null);
        jdbcTemplate.update("UPDATE outbox_events SET available_at = now() - INTERVAL '1 minute', lease_token = NULL, lease_until = NULL WHERE event_id = ?", initialOutbox.eventId());

        // 5. Run outbox publisher: successfully publishes to recovered broker
        int publishedAfterRecovery = outboxPublisherService.publishDueEvents();
        assertThat(publishedAfterRecovery).isGreaterThanOrEqualTo(1);

        OutboxEventRecord recoveredOutbox = outboxRepository.findById(initialOutbox.eventId()).orElseThrow();
        assertThat(recoveredOutbox.publishedAt()).isNotNull();

        // 6. Verify Kafka received the message, poll and process it
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        ConsumerRecord<String, String> matched = null;
        for (ConsumerRecord<String, String> r : records) {
            if (transferId.toString().equals(r.key())) {
                matched = r;
                break;
            }
        }
        assertThat(matched).isNotNull();

        EventEnvelope envelope = objectMapper.readValue(matched.value(), EventEnvelope.class);
        asyncTransferProcessor.processTransferEvent(envelope);

        // 7. Verify transfer completed successfully
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
    }
}
