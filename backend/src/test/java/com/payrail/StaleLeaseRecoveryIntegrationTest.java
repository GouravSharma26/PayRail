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

    @Test
    @DisplayName("Finding 3: Original outbox event published, transfer still PENDING -> recovery assigns sequence 2 (no collision)")
    void shouldRecoverPendingTransferWhenOriginalOutboxEventWasPublished() throws Exception {
        String idempotencyKey = "key-stale-pending-pub-" + UUID.randomUUID();
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

        // 1. Mark the original outbox event (seq 1) as published
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET published_at = now()
                WHERE aggregate_id = ? AND aggregate_sequence = 1
                """, transferId);

        // 2. Make transfer older than the stale pending threshold (e.g. 60s ago)
        jdbcTemplate.update("""
                UPDATE transfers
                SET created_at = now() - INTERVAL '60 seconds'
                WHERE id = ?
                """, transferId);

        // 3. Run stale pending recovery sweep
        int recoveredCount = processingRecoveryService.sweepStalePendingTransfers();
        assertThat(recoveredCount).isEqualTo(1);

        // 4. Verify recovery outbox event was created with aggregate_sequence = 2 (NOT 1!)
        List<OutboxEventRecord> events = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();

        assertThat(events).hasSize(1);
        OutboxEventRecord recoveryEvent = events.get(0);
        assertThat(recoveryEvent.aggregateSequence()).isEqualTo(2L);
        assertThat(recoveryEvent.eventType()).isEqualTo("transfer.requested");

        // 5. Complete processing with this recovered event
        EventEnvelope env = new EventEnvelope(
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
        asyncTransferProcessor.processTransferEvent(env);

        TransferRecord finalTransfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(finalTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Finding 3: Pending transfer with no outbox event at all is recovered with sequence 1")
    void shouldRecoverPendingTransferWithNoOutboxEventAtAll() throws Exception {
        String idempotencyKey = "key-no-outbox-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "40.00", "INR");

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

        // 1. Delete all outbox events for this transfer to simulate outbox loss / crash before outbox write
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id = ?", transferId);

        // 2. Make transfer older than stale pending threshold
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '60 seconds' WHERE id = ?", transferId);

        // 3. Run sweep
        int recovered = processingRecoveryService.sweepStalePendingTransfers();
        assertThat(recovered).isEqualTo(1);

        // 4. Verify outbox event created with aggregate_sequence = 1
        OutboxEventRecord newEvent = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();

        assertThat(newEvent.aggregateSequence()).isEqualTo(1L);
        assertThat(newEvent.eventType()).isEqualTo("transfer.requested");
    }

    @Test
    @DisplayName("Finding 3: Two recovery sweepers racing on the same pending transfer create exactly 1 outbox event")
    void shouldSafelyHandleTwoRecoverySweepersRacingOnSamePendingTransfer() throws Exception {
        String idempotencyKey = "key-race-sweep-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "35.00", "INR");

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

        // Simulate published original outbox and stale transfer
        jdbcTemplate.update("UPDATE outbox_events SET published_at = now() WHERE aggregate_id = ?", transferId);
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '60 seconds' WHERE id = ?", transferId);

        // Run sweepers concurrently
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<Integer> f1 = executor.submit(() -> processingRecoveryService.sweepStalePendingTransfers());
        java.util.concurrent.Future<Integer> f2 = executor.submit(() -> processingRecoveryService.sweepStalePendingTransfers());

        int res1 = f1.get();
        int res2 = f2.get();
        executor.shutdown();

        // Exactly one sweeper recovers it (total recovered between both = 1)
        assertThat(res1 + res2).isEqualTo(1);

        // Exactly 1 unpublished recovery outbox event exists
        List<OutboxEventRecord> unpubEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();

        assertThat(unpubEvents).hasSize(1);
        assertThat(unpubEvents.get(0).aggregateSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Finding 3: Consumer claims transfer while recovery sweep is selecting candidates -> sweeper skips")
    void shouldSkipRecoveryIfConsumerClaimsTransferWhileSweepIsRunning() throws Exception {
        String idempotencyKey = "key-consumer-claim-race-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "25.00", "INR");

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

        jdbcTemplate.update("UPDATE outbox_events SET published_at = now() WHERE aggregate_id = ?", transferId);
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '60 seconds' WHERE id = ?", transferId);

        // Worker claims transfer: status becomes PROCESSING
        UUID claimToken = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = now() + INTERVAL '30 seconds',
                    processing_attempt_count = 1
                WHERE id = ?
                """, claimToken, transferId);

        // Sweeper runs: candidate is locked, but status check sees PROCESSING and skips
        int recovered = processingRecoveryService.sweepStalePendingTransfers();
        assertThat(recovered).isEqualTo(0);

        List<OutboxEventRecord> unpubEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();

        assertThat(unpubEvents).isEmpty();
    }

    @Test
    @DisplayName("Finding 3: Failure or conflict on one candidate does not abort sweep for remaining candidates")
    void shouldNotAbortSweepWhenOneCandidateEncounteredError() throws Exception {
        String key1 = "key-iso-1-" + UUID.randomUUID();
        String key2 = "key-iso-2-" + UUID.randomUUID();

        UUID transfer1 = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(new CreateTransferRequest(accountAId, accountBId, "10.00", "INR"), headersWith(key1)),
                TransferResponse.class).getBody().id();

        UUID transfer2 = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(new CreateTransferRequest(accountAId, accountBId, "20.00", "INR"), headersWith(key2)),
                TransferResponse.class).getBody().id();

        jdbcTemplate.update("UPDATE outbox_events SET published_at = now() WHERE aggregate_id IN (?, ?)", transfer1, transfer2);
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '60 seconds' WHERE id IN (?, ?)", transfer1, transfer2);

        // Modify transfer1 status to PROCESSING with active lease so recoverPendingTransfer for transfer1 returns false
        jdbcTemplate.update("UPDATE transfers SET status = 'PROCESSING', processing_claim_token = gen_random_uuid(), processing_lease_until = now() + INTERVAL '1 hour' WHERE id = ?", transfer1);

        // Run sweep: transfer 1 is skipped, transfer 2 is successfully recovered
        int recovered = processingRecoveryService.sweepStalePendingTransfers();
        assertThat(recovered).isEqualTo(1);

        List<OutboxEventRecord> unpubFor2 = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transfer2))
                .toList();

        assertThat(unpubFor2).hasSize(1);
        assertThat(unpubFor2.get(0).aggregateSequence()).isEqualTo(2L);
    }

    private HttpHeaders headersWith(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        return headers;
    }
}
