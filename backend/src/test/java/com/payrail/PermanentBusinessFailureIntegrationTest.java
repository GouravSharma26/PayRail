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
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.ProcessingAttemptRecord;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.repository.ProcessingAttemptRepository;
import com.payrail.transfer.repository.TransferRepository;
import com.payrail.transfer.repository.TransferStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PermanentBusinessFailureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AsyncTransferProcessor asyncTransferProcessor;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private ProcessingAttemptRepository processingAttemptRepository;

    @Autowired
    private TransferStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Business Fail A", "INR", "100.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Business Fail B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();
    }

    @Test
    @DisplayName("Permanent business decline (insufficient funds) results in terminal FAILED with zero ledger entries and no retries")
    void shouldTerminateOnBusinessDeclineWithoutLedgerOrRetries() throws Exception {
        String idempotencyKey = "key-biz-fail-" + UUID.randomUUID();
        // Request 500.00 when account only has 100.00
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "500.00", "INR");

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
        UUID eventId = UUID.randomUUID();

        EventEnvelope envelope = new EventEnvelope(
                eventId,
                "transfer.requested",
                1,
                Instant.now(),
                "transfer",
                transferId,
                1L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );

        // Process through async processor
        asyncTransferProcessor.processTransferEvent(envelope);

        // 1. Verify transfer is terminal FAILED with code INSUFFICIENT_FUNDS
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(transfer.completedAt()).isNotNull();

        // 2. Verify ZERO ledger entries
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).isEmpty();

        // 3. Verify account balances remain completely untouched
        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("500.00"));

        // 4. Verify processing attempt outcome is BUSINESS_FAILED
        List<ProcessingAttemptRecord> attempts = processingAttemptRepository.findByTransferId(transferId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).outcome()).isEqualTo("BUSINESS_FAILED");
        assertThat(attempts.get(0).errorCode()).isEqualTo("INSUFFICIENT_FUNDS");

        // 5. Verify status history transitions: PENDING -> PROCESSING -> FAILED
        List<TransferStatusHistoryRecord> history = statusHistoryRepository.findByTransferId(transferId);
        assertThat(history.stream().map(TransferStatusHistoryRecord::toStatus).toList())
                .containsExactly(TransferStatus.PENDING, TransferStatus.PROCESSING, TransferStatus.FAILED);

        // 6. Verify NO retry outbox events are scheduled
        long retryOutboxCount = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId) && "transfer.retry".equals(e.eventType()))
                .count();
        assertThat(retryOutboxCount).isEqualTo(0);
    }
}
