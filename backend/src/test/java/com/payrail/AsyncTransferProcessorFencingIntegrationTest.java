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

class AsyncTransferProcessorFencingIntegrationTest extends BaseIntegrationTest {

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
                new CreateAccountRequest("Fencing Test A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Fencing Test B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();
    }

    @Test
    @DisplayName("Finding 4: Stale worker resumes failure handling after newer worker committed SUCCEEDED -> state remains unchanged, no duplicate retries or postings")
    void shouldNotModifyTransferOrEnqueueRetryWhenStaleWorkerResumesAfterNewerWorkerCommittedSucceeded() throws Exception {
        String idempotencyKey = "key-fence-succ-" + UUID.randomUUID();
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

        // 1. Worker 1 claims transfer with token1
        UUID staleClaimToken = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = ?,
                    processing_attempt_count = 1
                WHERE id = ?
                """, staleClaimToken, java.sql.Timestamp.from(now.plusSeconds(30)), transferId);

        // 2. Newer Worker 2 takes over and successfully processes the transfer to SUCCEEDED
        EventEnvelope validEnvelope = new EventEnvelope(
                UUID.randomUUID(),
                "transfer.requested",
                1,
                now,
                "transfer",
                transferId,
                1L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );

        // Expire Worker 1's lease to simulate timeout
        jdbcTemplate.update("""
                UPDATE transfers
                SET processing_lease_until = now() - INTERVAL '1 second'
                WHERE id = ?
                """, transferId);

        // Worker 2 takes over and processes the event successfully to SUCCEEDED
        asyncTransferProcessor.processTransferEvent(validEnvelope);

        // Clear outbox events so we can detect any rogue retries created by Worker 1
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id = ?", transferId);

        // 3. Stale Worker 1 wakes up and calls failure handling with staleClaimToken and attempt 1
        asyncTransferProcessor.handleTechnicalFailure(
                transferId,
                staleClaimToken,
                1,
                validEnvelope,
                new RuntimeException("Simulated delayed DB timeout from Worker 1"),
                now.minusSeconds(10)
        );

        // 4. Verify invariant guarantees:
        // - Transfer remains SUCCEEDED
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);

        // - Exactly 2 ledger entries (no duplicate financial postings)
        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        assertThat(entries).hasSize(2);

        // - Balances are unchanged
        AccountRecord accA = accountRepository.findById(accountAId).orElseThrow();
        AccountRecord accB = accountRepository.findById(accountBId).orElseThrow();
        assertThat(accA.currentBalance()).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(accB.currentBalance()).isEqualByComparingTo(new BigDecimal("700.00"));

        // - Zero outbox retry events were created by the stale worker
        List<OutboxEventRecord> outboxEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();
        assertThat(outboxEvents).isEmpty();

        // - Status history does not record an invalid transition after SUCCEEDED
        List<TransferStatusHistoryRecord> history = statusHistoryRepository.findByTransferId(transferId);
        assertThat(history.stream().noneMatch(h -> h.fromStatus() == TransferStatus.SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("Finding 4: Stale worker resumes failure handling after another worker committed permanent business decline FAILED -> remains FAILED, no retries")
    void shouldNotModifyTransferOrEnqueueRetryWhenStaleWorkerResumesAfterAnotherWorkerCommittedPermanentBusinessDecline() throws Exception {
        String idempotencyKey = "key-fence-fail-" + UUID.randomUUID();
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

        // 1. Worker 1 claims transfer
        UUID staleClaimToken = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = ?,
                    processing_attempt_count = 1
                WHERE id = ?
                """, staleClaimToken, java.sql.Timestamp.from(now.plusSeconds(30)), transferId);

        // 2. Worker 2 takes over and commits permanent business decline
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'FAILED',
                    failure_code = 'INSUFFICIENT_FUNDS',
                    failure_message = 'Source account has insufficient balance',
                    processing_claim_token = NULL,
                    processing_lease_until = NULL,
                    completed_at = now()
                WHERE id = ?
                """, transferId);

        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id = ?", transferId);

        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                "transfer.requested",
                1,
                now,
                "transfer",
                transferId,
                1L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );

        // 3. Stale Worker 1 wakes up and attempts failure handling
        asyncTransferProcessor.handleTechnicalFailure(
                transferId,
                staleClaimToken,
                1,
                envelope,
                new RuntimeException("Late network timeout from Worker 1"),
                now.minusSeconds(5)
        );

        // 4. Verify invariants:
        // - Transfer remains terminal FAILED with business decline code
        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(finalTransfer.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");

        // - Zero ledger entries
        assertThat(ledgerRepository.findByTransferId(transferId)).isEmpty();

        // - Zero retry outbox events
        List<OutboxEventRecord> outboxEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();
        assertThat(outboxEvents).isEmpty();
    }

    @Test
    @DisplayName("Finding 4: Stale worker resumes failure handling after claim was replaced by active newer worker -> does not overwrite newer claim")
    void shouldNotModifyTransferOrEnqueueRetryWhenStaleWorkerResumesAfterClaimWasReplacedByActiveNewerWorker() throws Exception {
        String idempotencyKey = "key-fence-replace-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "80.00", "INR");

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

        // 1. Worker 1 claimed with token 1
        UUID staleClaimToken = UUID.randomUUID();
        UUID activeClaimToken = UUID.randomUUID();
        Instant now = Instant.now();

        // Worker 2 replaced the claim with activeClaimToken and attempt 2
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = ?,
                    processing_attempt_count = 2
                WHERE id = ?
                """, activeClaimToken, java.sql.Timestamp.from(now.plusSeconds(60)), transferId);

        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id = ?", transferId);

        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                "transfer.requested",
                1,
                now,
                "transfer",
                transferId,
                1L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString())))
        );

        // 2. Stale Worker 1 calls failure handling with staleClaimToken (attempt 1)
        asyncTransferProcessor.handleTechnicalFailure(
                transferId,
                staleClaimToken,
                1,
                envelope,
                new RuntimeException("Delayed connection timeout"),
                now.minusSeconds(10)
        );

        // 3. Verify transfer was not modified by Worker 1
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(transfer.processingClaimToken()).isEqualTo(activeClaimToken);
        assertThat(transfer.processingAttemptCount()).isEqualTo(2);

        // Zero retry outbox events enqueued by stale worker
        List<OutboxEventRecord> outboxEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();
        assertThat(outboxEvents).isEmpty();
    }
}
