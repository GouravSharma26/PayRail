package com.payrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.repository.AccountRepository;
import com.payrail.consumer.service.AsyncTransferProcessor;
import com.payrail.consumer.service.ProcessingRecoveryService;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
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

class StaleLeaseRecoveryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProcessingRecoveryService processingRecoveryService;

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
    private TransferStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void setup() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Stale Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Stale Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();
    }

    @Test
    @DisplayName("Should detect expired processing lease, reset transfer to PENDING, and recover via enqueued outbox event")
    void shouldRecoverStaleProcessingLease() throws Exception {
        String idempotencyKey = "key-stale-" + UUID.randomUUID();
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

        // 1. Simulate worker crash mid-flight: claim transfer and expire the lease in the past
        UUID deadWorkerClaimToken = UUID.randomUUID();
        Instant expiredTime = Instant.now().minusSeconds(120);

        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = ?,
                    processing_attempt_count = 1
                WHERE id = ?
                """, deadWorkerClaimToken, java.sql.Timestamp.from(expiredTime), transferId);

        // 2. Run recovery sweep
        int recoveredCount = processingRecoveryService.sweepStaleProcessingLeases();
        assertThat(recoveredCount).isEqualTo(1);

        // 3. Verify transfer is returned to PENDING with lease cleared
        TransferRecord transferAfterSweep = transferRepository.findById(transferId).orElseThrow();
        assertThat(transferAfterSweep.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(transferAfterSweep.processingClaimToken()).isNull();
        assertThat(transferAfterSweep.processingLeaseUntil()).isNull();

        // 4. Verify status history has STALE_LEASE_RECOVERED transition
        List<TransferStatusHistoryRecord> history = statusHistoryRepository.findByTransferId(transferId);
        assertThat(history.stream().anyMatch(h -> "STALE_LEASE_RECOVERED".equals(h.reasonCode()))).isTrue();

        // 5. Verify recovery outbox event was generated
        OutboxEventRecord recoveryEvent = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId) && "transfer.recovery".equals(e.eventType()))
                .findFirst()
                .orElseThrow();

        // 6. Complete processing using the recovery event
        EventEnvelope recoveryEnvelope = new EventEnvelope(
                recoveryEvent.eventId(),
                recoveryEvent.eventType(),
                recoveryEvent.schemaVersion(),
                Instant.now(),
                "transfer",
                transferId,
                recoveryEvent.aggregateSequence(),
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );

        asyncTransferProcessor.processTransferEvent(recoveryEnvelope);

        // 7. Verify transfer completes as SUCCEEDED with accurate ledger legs and balances
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("880.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("620.00"));
    }
}
