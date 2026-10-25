package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.consumer.service.AsyncProcessingFailureInjector;
import com.payrail.consumer.service.AsyncTransferProcessor;
import com.payrail.consumer.service.DeadLetterPublisher;
import com.payrail.consumer.service.TransferCommandConsumer;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.repository.TransferRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferCommandConsumerErrorPropagationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferCommandConsumer transferCommandConsumer;

    @Autowired
    private AsyncTransferProcessor asyncTransferProcessor;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeadLetterPublisher deadLetterPublisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Consumer Err Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Consumer Err Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();
    }

    @AfterEach
    void tearDown() {
        asyncTransferProcessor.setFailureInjector(null);
        deadLetterPublisher.setFailureInjector(null);
    }

    @Test
    @DisplayName("DLQ metric does not increment when malformed payload cannot be durably recorded")
    void shouldNotCountMalformedPayloadAsDlqWhenPersistenceFails() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                "transfer.requested",
                1,
                Instant.now(),
                "transfer",
                UUID.randomUUID(),
                1L,
                UUID.randomUUID(),
                objectMapper.readTree("{\"transferId\":\"not-a-uuid\"}")
        );
        double dlqCountBefore = meterRegistry.counter("payrail.consumer.events_dlq").count();
        deadLetterPublisher.setFailureInjector(dlq -> {
            throw new DataAccessResourceFailureException("Simulated DLQ persistence failure");
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asyncTransferProcessor.processTransferEvent(envelope))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("Simulated DLQ persistence failure");

        assertThat(meterRegistry.counter("payrail.consumer.events_dlq").count()).isEqualTo(dlqCountBefore);
        assertThat(outboxRepository.findUnpublished(Instant.now().plusSeconds(60)))
                .noneMatch(event -> "transfer.dlq".equals(event.eventType())
                        && eventId.equals(event.correlationId()));
    }

    @Test
    @DisplayName("Finding 5: Database failure before durable claim propagates to container error policy and does not silently lose source event")
    void shouldPropagateExceptionWhenDatabaseFailsBeforeDurableClaim() throws Exception {
        String idempotencyKey = "key-err-preclaim-" + UUID.randomUUID();
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

        EventEnvelope envelope = new EventEnvelope(
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

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                transferCommandsTopic,
                0,
                100L,
                transferId.toString(),
                objectMapper.writeValueAsString(envelope)
        );

        // Inject simulated database connection failure before claim
        asyncTransferProcessor.setFailureInjector(new AsyncProcessingFailureInjector() {
            @Override
            public void beforePostingCommit(TransferRecord transfer, int attemptNumber) {}

            @Override
            public void beforeClaim(UUID id) {
                if (transferId.equals(id)) {
                    throw new DataAccessResourceFailureException("Simulated database failure before durable claim");
                }
            }
        });

        // Verify onMessage re-throws exception and does not silently return / commit offset
        assertThatThrownBy(() -> transferCommandConsumer.onMessage(record))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("Simulated database failure before durable claim");

        // Verify transfer remains PENDING with 0 processing attempts (not lost or mutated)
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.processingAttemptCount()).isEqualTo(0);
        assertThat(transfer.processingClaimToken()).isNull();
    }

    @Test
    @DisplayName("Finding 5: Failure in retry-state transaction propagates and does not result in a successful listener return")
    void shouldPropagateExceptionWhenFailureOccursInRetryStateTransaction() throws Exception {
        String idempotencyKey = "key-err-retrytx-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "120.00", "INR");

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

        EventEnvelope envelope = new EventEnvelope(
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

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                transferCommandsTopic,
                0,
                101L,
                transferId.toString(),
                objectMapper.writeValueAsString(envelope)
        );

        // Inject technical posting failure, followed by database failure inside the retry-state transaction
        asyncTransferProcessor.setFailureInjector(new AsyncProcessingFailureInjector() {
            @Override
            public void beforePostingCommit(TransferRecord transfer, int attemptNumber) {
                throw new RuntimeException("Transient posting error");
            }

            @Override
            public void inRetryStateTransaction(UUID id, int attemptNumber) {
                if (transferId.equals(id)) {
                    throw new CannotCreateTransactionException("Simulated DB connection failure during retry-state transaction");
                }
            }
        });

        // Verify onMessage does NOT return successfully; it must propagate the exception to the container
        assertThatThrownBy(() -> transferCommandConsumer.onMessage(record))
                .isInstanceOf(CannotCreateTransactionException.class)
                .hasMessageContaining("Simulated DB connection failure during retry-state transaction");
    }
}
