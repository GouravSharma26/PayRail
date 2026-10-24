package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.repository.AccountRepository;
import com.payrail.consumer.repository.ProcessedMessagesRepository;
import com.payrail.consumer.service.AsyncTransferProcessor;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.transfer.domain.ProcessingAttemptRecord;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.repository.ProcessingAttemptRepository;
import com.payrail.transfer.repository.TransferRepository;
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

class BrokerConsumerDeduplicationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AsyncTransferProcessor asyncTransferProcessor;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private ProcessedMessagesRepository processedMessagesRepository;

    @Autowired
    private ProcessingAttemptRepository processingAttemptRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Dedupe Acc A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Dedupe Acc B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();
    }

    @Test
    @DisplayName("Should process transfer asynchronously and absorb duplicate event delivery without repeating postings")
    void shouldProcessTransferAndAbsorbDuplicateDelivery() throws Exception {
        String idempotencyKey = "key-dedupe-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "200.00", "INR");

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

        // First delivery: processes transfer to SUCCEEDED
        asyncTransferProcessor.processTransferEvent(envelope);

        // 1. Verify transfer status is SUCCEEDED
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(transfer.processingAttemptCount()).isEqualTo(1);
        assertThat(transfer.completedAt()).isNotNull();

        // 2. Verify account balances
        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("700.00"));

        // 3. Verify exactly one debit and one credit ledger entry
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().filter(e -> e.leg() == LedgerLeg.DEBIT).count()).isEqualTo(1);
        assertThat(entries.stream().filter(e -> e.leg() == LedgerLeg.CREDIT).count()).isEqualTo(1);

        // 4. Verify processed message dedupe record
        assertThat(processedMessagesRepository.exists("transfer-worker", eventId)).isTrue();

        // 5. Verify processing attempt record
        List<ProcessingAttemptRecord> attempts = processingAttemptRepository.findByTransferId(transferId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).outcome()).isEqualTo("SUCCEEDED");

        // Second delivery: simulate exact duplicate broker redelivery of the same event
        asyncTransferProcessor.processTransferEvent(envelope);

        // 6. Assert zero state drift: balances, ledger legs, and attempts remain strictly identical
        AccountRecord accAPost = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accBPost = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accAPost.currentBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(accBPost.currentBalance()).isEqualByComparingTo(new BigDecimal("700.00"));

        List<LedgerEntryRecord> entriesPost = ledgerRepository.findByTransferId(transferId);
        assertThat(entriesPost).hasSize(2);

        List<ProcessingAttemptRecord> attemptsPost = processingAttemptRepository.findByTransferId(transferId);
        assertThat(attemptsPost).hasSize(1);
    }
}
