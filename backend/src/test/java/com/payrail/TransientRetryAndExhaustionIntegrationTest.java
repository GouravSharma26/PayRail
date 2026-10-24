package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.repository.AccountRepository;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.consumer.service.AsyncTransferProcessor;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.ProcessingAttemptRecord;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.repository.ProcessingAttemptRepository;
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
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransientRetryAndExhaustionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AsyncTransferProcessor asyncTransferProcessor;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ProcessingAttemptRepository processingAttemptRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.payrail.outbox.service.OutboxPublisherService outboxPublisherService;

    @Value("${payrail.topics.transfer-commands-dlq:payrail.transfer-commands.dlq.v1}")
    private String dlqTopic;

    private Consumer<String, String> dlqConsumer;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Retry Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Retry Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        dlqConsumer = factory.createConsumer();
        dlqConsumer.subscribe(Collections.singletonList(dlqTopic));
    }

    @AfterEach
    void tearDown() {
        asyncTransferProcessor.setFailureInjector(null);
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
    }

    @Test
    @DisplayName("Should schedule durable retry on transient failure and succeed on subsequent attempt")
    void shouldRetryTransientFailureAndSucceedOnSecondAttempt() throws Exception {
        String idempotencyKey = "key-retry-succ-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "100.00", "INR");

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

        // 1. Inject failure ONLY on attempt 1
        asyncTransferProcessor.setFailureInjector((transfer, attemptNumber) -> {
            if (attemptNumber == 1) {
                throw new RuntimeException("Simulated transient network timeout on attempt 1");
            }
        });

        EventEnvelope envelope1 = new EventEnvelope(
                UUID.randomUUID(),
                "transfer.requested",
                1,
                Instant.now(),
                "transfer",
                transferId,
                1L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );

        // Process attempt 1 (fails transiently)
        asyncTransferProcessor.processTransferEvent(envelope1);

        // Verify attempt 1 recorded RETRY_SCHEDULED and transfer returned to PENDING
        TransferRecord transferAfterAttempt1 = transferRepository.findById(transferId).orElseThrow();
        assertThat(transferAfterAttempt1.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(transferAfterAttempt1.processingAttemptCount()).isEqualTo(1);

        List<ProcessingAttemptRecord> attempts1 = processingAttemptRepository.findByTransferId(transferId);
        assertThat(attempts1).hasSize(1);
        assertThat(attempts1.get(0).outcome()).isEqualTo("RETRY_SCHEDULED");

        // Verify durable retry outbox event scheduled
        OutboxEventRecord retryEvent = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId) && "transfer.retry".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(retryEvent.aggregateSequence()).isEqualTo(2L);

        // 2. Clear failure injector for attempt 2
        asyncTransferProcessor.setFailureInjector(null);

        EventEnvelope envelope2 = new EventEnvelope(
                retryEvent.eventId(),
                retryEvent.eventType(),
                retryEvent.schemaVersion(),
                Instant.now(),
                "transfer",
                transferId,
                2L,
                UUID.randomUUID(),
                objectMapper.readTree(retryEvent.payloadJson())
        );

        // Process attempt 2 (succeeds)
        asyncTransferProcessor.processTransferEvent(envelope2);

        // Verify transfer is SUCCEEDED
        TransferRecord transferFinal = transferRepository.findById(transferId).orElseThrow();
        assertThat(transferFinal.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(transferFinal.processingAttemptCount()).isEqualTo(2);

        List<ProcessingAttemptRecord> attempts2 = processingAttemptRepository.findByTransferId(transferId);
        assertThat(attempts2).hasSize(2);
        assertThat(attempts2.get(1).outcome()).isEqualTo("SUCCEEDED");

        // Verify financial postings: exactly 1 debit and 1 credit
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    @DisplayName("Should exhaust retries after max attempts, mark transfer FAILED, and route to DLQ")
    void shouldExhaustRetriesAndRouteToDlq() throws Exception {
        String idempotencyKey = "key-exhaust-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "100.00", "INR");

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

        // Inject permanent technical failure on ALL attempts
        asyncTransferProcessor.setFailureInjector((transfer, attemptNumber) -> {
            throw new RuntimeException("Simulated persistent database outage on attempt " + attemptNumber);
        });

        // Run attempts 1 to 5 (maxAttempts is 5)
        for (int i = 1; i <= 5; i++) {
            EventEnvelope env = new EventEnvelope(
                    UUID.randomUUID(),
                    i == 1 ? "transfer.requested" : "transfer.retry",
                    1,
                    Instant.now(),
                    "transfer",
                    transferId,
                    (long) i,
                    UUID.randomUUID(),
                    objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
            );
            asyncTransferProcessor.processTransferEvent(env);
        }

        // 1. Verify transfer is marked terminal FAILED with code RETRY_EXHAUSTED
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.failureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(transfer.processingAttemptCount()).isEqualTo(5);

        // 2. Verify all 5 attempts recorded, final attempt EXHAUSTED
        List<ProcessingAttemptRecord> attempts = processingAttemptRepository.findByTransferId(transferId);
        assertThat(attempts).hasSize(5);
        assertThat(attempts.get(4).outcome()).isEqualTo("EXHAUSTED");

        // 3. Verify zero ledger entries written
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).isEmpty();

        // 4. Verify message emitted to DLQ topic via leased outbox publisher
        int dlqPublished = outboxPublisherService.publishDueEvents();
        assertThat(dlqPublished).isGreaterThanOrEqualTo(1);

        ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofSeconds(10));
        assertThat(dlqRecords.isEmpty()).isFalse();

        ConsumerRecord<String, String> matchedDlqRecord = null;
        for (ConsumerRecord<String, String> record : dlqRecords) {
            if (transferId.toString().equals(record.key())) {
                matchedDlqRecord = record;
                break;
            }
        }

        assertThat(matchedDlqRecord).isNotNull();
        DeadLetterEnvelope dlqEnvelope = objectMapper.readValue(matchedDlqRecord.value(), DeadLetterEnvelope.class);
        assertThat(dlqEnvelope.transferId()).isEqualTo(transferId);
        assertThat(dlqEnvelope.failureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(dlqEnvelope.attemptCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Finding 1: DLQ record for retry-exhausted transfer is durable across broker outage and delivered upon recovery without repeating financial effects")
    void shouldDeliverDlqRecordDurableAcrossBrokerOutageForRetryExhaustedTransfer() throws Exception {
        String idempotencyKey = "key-exhaust-outage-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "75.00", "INR");

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

        // 1. Inject permanent technical failure on ALL attempts
        asyncTransferProcessor.setFailureInjector((transfer, attemptNumber) -> {
            throw new RuntimeException("Simulated service failure on attempt " + attemptNumber);
        });

        // 2. Run attempts 1 to 5 to reach retry exhaustion
        for (int i = 1; i <= 5; i++) {
            EventEnvelope env = new EventEnvelope(
                    UUID.randomUUID(),
                    i == 1 ? "transfer.requested" : "transfer.retry",
                    1,
                    Instant.now(),
                    "transfer",
                    transferId,
                    (long) i,
                    UUID.randomUUID(),
                    objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
            );
            asyncTransferProcessor.processTransferEvent(env);
        }

        // 3. Verify transfer is marked FAILED and DLQ record is durably stored in PostgreSQL outbox_events
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.failureCode()).isEqualTo("RETRY_EXHAUSTED");

        List<OutboxEventRecord> unpublished = outboxRepository.findUnpublished(Instant.now().plusSeconds(60));
        OutboxEventRecord dlqEvent = unpublished.stream()
                .filter(e -> "transfer.dlq".equals(e.eventType()) && transferId.equals(e.aggregateId()))
                .findFirst()
                .orElseThrow();
        assertThat(dlqEvent.publishedAt()).isNull();

        // Verify zero financial ledger entries
        assertThat(ledgerRepository.findByTransferId(transferId)).isEmpty();

        // 4. Broker recovers: leased outbox publisher delivers the DLQ record
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        OutboxEventRecord updatedDlq = outboxRepository.findById(dlqEvent.eventId()).orElseThrow();
        assertThat(updatedDlq.publishedAt()).isNotNull();

        // 5. Verify received on Kafka DLQ topic
        ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofSeconds(10));
        assertThat(dlqRecords.isEmpty()).isFalse();

        ConsumerRecord<String, String> matchedRecord = null;
        for (ConsumerRecord<String, String> r : dlqRecords) {
            if (transferId.toString().equals(r.key())) {
                matchedRecord = r;
                break;
            }
        }
        assertThat(matchedRecord).isNotNull();
        DeadLetterEnvelope receivedDlq = objectMapper.readValue(matchedRecord.value(), DeadLetterEnvelope.class);
        assertThat(receivedDlq.transferId()).isEqualTo(transferId);
        assertThat(receivedDlq.failureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(receivedDlq.attemptCount()).isEqualTo(5);

        // 6. Deduplication guard: Duplicate delivery arrives for terminal transfer
        EventEnvelope duplicateEnvelope = new EventEnvelope(
                UUID.randomUUID(),
                "transfer.retry",
                1,
                Instant.now(),
                "transfer",
                transferId,
                6L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );
        asyncTransferProcessor.processTransferEvent(duplicateEnvelope);

        // 7. Verify terminal state unchanged and zero ledger entries
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(ledgerRepository.findByTransferId(transferId)).isEmpty();

        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("500.00"));
    }
}
