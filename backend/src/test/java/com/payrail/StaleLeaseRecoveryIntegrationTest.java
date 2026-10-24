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
import com.payrail.transfer.domain.ProcessingAttemptRecord;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = {
        "payrail.processing.consumer-enabled=false",
        "payrail.processing.recovery-sweep-enabled=false",
        "payrail.outbox.publisher-enabled=false"
})
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
    private com.payrail.transfer.repository.ProcessingAttemptRepository processingAttemptRepository;

    @Autowired
    private com.payrail.outbox.service.OutboxPublisherService outboxPublisherService;

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

        processingAttemptRepository.insert(new ProcessingAttemptRecord(
                UUID.randomUUID(),
                transferId,
                1,
                deadWorkerClaimToken,
                "IN_PROGRESS",
                null,
                null,
                expiredTime,
                null,
                null
        ));

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

    @Test
    @DisplayName("Finding 2: Pending transfer with exhausted outbox event does not regenerate command events across repeated sweeps")
    void shouldNotRegenerateCommandEventsForTransferWithExhaustedOutboxEvent() throws Exception {
        String idempotencyKey = "key-exhausted-no-regen-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "45.00", "INR");

        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headersWith(idempotencyKey)),
                TransferResponse.class
        );
        UUID transferId = response.getBody().id();

        // Simulate publisher exhaustion: published_at IS NULL, last_error_code = 'PUBLISH_RETRY_EXHAUSTED', publish_attempts = 5
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET last_error_code = 'PUBLISH_RETRY_EXHAUSTED',
                    last_error_message = 'Max publish attempts reached (5)',
                    publish_attempts = 5,
                    lease_token = NULL,
                    lease_until = NULL
                WHERE aggregate_id = ?
                """, transferId);

        // Age transfer past stale threshold
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '60 seconds' WHERE id = ?", transferId);

        // Run recovery sweep repeatedly
        int sweep1 = processingRecoveryService.sweepStalePendingTransfers();
        int sweep2 = processingRecoveryService.sweepStalePendingTransfers();
        assertThat(sweep1).isEqualTo(0);
        assertThat(sweep2).isEqualTo(0);

        // Verify transfer has exactly 1 outbox event (the exhausted one), no duplicate replacements generated
        List<OutboxEventRecord> events = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferId))
                .toList();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).lastErrorCode()).isEqualTo("PUBLISH_RETRY_EXHAUSTED");
        assertThat(events.get(0).aggregateSequence()).isEqualTo(1L);

        // Verify operator replay resets the event and allows publisher to deliver it
        boolean replayed = outboxRepository.resetForReplay(events.get(0).eventId(), Instant.now());
        assertThat(replayed).isTrue();

        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        OutboxEventRecord afterReplay = outboxRepository.findById(events.get(0).eventId()).orElseThrow();
        assertThat(afterReplay.publishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Finding 2: Unrelated pending transfer still recovers when another transfer has an exhausted outbox event")
    void shouldRecoverUnrelatedTransferWhenOneCandidateHasExhaustedOutboxEvent() throws Exception {
        String keyExhausted = "key-exhausted-cand-" + UUID.randomUUID();
        String keyRecoverable = "key-rec-cand-" + UUID.randomUUID();

        UUID transferExhausted = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(new CreateTransferRequest(accountAId, accountBId, "15.00", "INR"), headersWith(keyExhausted)),
                TransferResponse.class).getBody().id();

        UUID transferRecoverable = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(new CreateTransferRequest(accountAId, accountBId, "25.00", "INR"), headersWith(keyRecoverable)),
                TransferResponse.class).getBody().id();

        // transferExhausted has unpublished event marked PUBLISH_RETRY_EXHAUSTED
        jdbcTemplate.update("UPDATE outbox_events SET last_error_code = 'PUBLISH_RETRY_EXHAUSTED', publish_attempts = 5 WHERE aggregate_id = ?", transferExhausted);

        // transferRecoverable has original event published but transfer is still PENDING
        jdbcTemplate.update("UPDATE outbox_events SET published_at = now() WHERE aggregate_id = ?", transferRecoverable);

        // Age both transfers
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '60 seconds' WHERE id IN (?, ?)", transferExhausted, transferRecoverable);

        // Run sweep: transferExhausted is skipped, transferRecoverable is recovered
        int recovered = processingRecoveryService.sweepStalePendingTransfers();
        assertThat(recovered).isEqualTo(1);

        List<OutboxEventRecord> unpubForRec = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> e.aggregateId().equals(transferRecoverable))
                .toList();

        assertThat(unpubForRec).hasSize(1);
        assertThat(unpubForRec.get(0).aggregateSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Finding 3: Stale lease recovery below attempt limit finalizes attempt as RETRY_SCHEDULED")
    void shouldFinalizeAttemptAsRetryScheduledWhenStaleLeaseRecoveredBelowLimit() throws Exception {
        String idempotencyKey = "key-finalize-retry-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "50.00", "INR");

        UUID transferId = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(request, headersWith(idempotencyKey)), TransferResponse.class).getBody().id();

        UUID claimToken = UUID.randomUUID();
        Instant startedAt = Instant.now().minusSeconds(120);

        // Claim transfer for attempt 1
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = now() - INTERVAL '10 seconds',
                    processing_attempt_count = 1
                WHERE id = ?
                """, claimToken, transferId);

        // Insert processing_attempts row with outcome = 'IN_PROGRESS'
        processingAttemptRepository.insert(new com.payrail.transfer.domain.ProcessingAttemptRecord(
                UUID.randomUUID(),
                transferId,
                1,
                claimToken,
                "IN_PROGRESS",
                null,
                null,
                startedAt,
                null,
                null
        ));

        // Sweep stale processing leases
        int recovered = processingRecoveryService.sweepStaleProcessingLeases();
        assertThat(recovered).isEqualTo(1);

        // Verify transfer state returned to PENDING
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.processingClaimToken()).isNull();

        // Verify attempt 1 was finalized with RETRY_SCHEDULED, duration, and error code
        com.payrail.transfer.domain.ProcessingAttemptRecord attempt = processingAttemptRepository
                .findByTransferIdAndAttempt(transferId, 1)
                .orElseThrow();

        assertThat(attempt.outcome()).isEqualTo("RETRY_SCHEDULED");
        assertThat(attempt.errorCode()).isEqualTo("STALE_LEASE_RECOVERED");
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(attempt.durationMs()).isGreaterThan(0L);

        // Verify status history
        List<TransferStatusHistoryRecord> history = statusHistoryRepository.findByTransferId(transferId);
        assertThat(history).anyMatch(h -> h.fromStatus() == TransferStatus.PROCESSING
                && h.toStatus() == TransferStatus.PENDING
                && "STALE_LEASE_RECOVERED".equals(h.reasonCode()));
    }

    @Test
    @DisplayName("Finding 3: Stale lease recovery at max attempts limit finalizes attempt as EXHAUSTED")
    void shouldFinalizeAttemptAsExhaustedWhenStaleLeaseRecoveredAtMaxAttemptsLimit() throws Exception {
        String idempotencyKey = "key-finalize-exhausted-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "60.00", "INR");

        UUID transferId = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(request, headersWith(idempotencyKey)), TransferResponse.class).getBody().id();

        UUID claimToken = UUID.randomUUID();
        Instant startedAt = Instant.now().minusSeconds(180);

        // Transfer is at attempt 5 (maxAttempts is 5) and lease expired
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = now() - INTERVAL '10 seconds',
                    processing_attempt_count = 5
                WHERE id = ?
                """, claimToken, transferId);

        // Insert processing_attempts row with outcome = 'IN_PROGRESS'
        processingAttemptRepository.insert(new com.payrail.transfer.domain.ProcessingAttemptRecord(
                UUID.randomUUID(),
                transferId,
                5,
                claimToken,
                "IN_PROGRESS",
                null,
                null,
                startedAt,
                null,
                null
        ));

        // Sweep stale processing leases
        int recovered = processingRecoveryService.sweepStaleProcessingLeases();
        assertThat(recovered).isEqualTo(1);

        // Verify transfer state is FAILED
        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.failureCode()).isEqualTo("LEASE_EXPIRED_EXHAUSTED");

        // Verify attempt 5 was finalized with EXHAUSTED
        com.payrail.transfer.domain.ProcessingAttemptRecord attempt = processingAttemptRepository
                .findByTransferIdAndAttempt(transferId, 5)
                .orElseThrow();

        assertThat(attempt.outcome()).isEqualTo("EXHAUSTED");
        assertThat(attempt.errorCode()).isEqualTo("LEASE_EXPIRED_EXHAUSTED");
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(attempt.durationMs()).isGreaterThan(0L);

        // Verify DLQ outbox event was recorded
        List<OutboxEventRecord> dlqEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600))
                .stream()
                .filter(e -> "transfer.dlq".equals(e.eventType()) && (transferId.equals(e.aggregateId()) || transferId.equals(e.correlationId())))
                .toList();

        assertThat(dlqEvents).hasSize(1);
        assertThat(dlqEvents.get(0).payloadJson()).contains("LEASE_EXPIRED_EXHAUSTED");

        // Verify zero financial ledger entries
        assertThat(ledgerRepository.findByTransferId(transferId)).isEmpty();
    }

    @Test
    @DisplayName("Finding 3: Stale recovery does not overwrite attempt record belonging to a newer worker")
    void shouldNotOverwriteAttemptRecordBelongingToNewerWorker() throws Exception {
        String idempotencyKey = "key-fencing-attempt-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, "70.00", "INR");

        UUID transferId = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(request, headersWith(idempotencyKey)), TransferResponse.class).getBody().id();

        UUID staleClaimToken = UUID.randomUUID();
        UUID newerClaimToken = UUID.randomUUID();

        // Stale attempt 1 already completed or claimed
        processingAttemptRepository.insert(new com.payrail.transfer.domain.ProcessingAttemptRecord(
                UUID.randomUUID(),
                transferId,
                1,
                newerClaimToken, // newer worker claimed it
                "IN_PROGRESS",
                null,
                null,
                Instant.now(),
                null,
                null
        ));

        // Call finalizeStaleAttempt with staleClaimToken
        boolean finalized = processingAttemptRepository.finalizeStaleAttempt(
                transferId,
                1,
                staleClaimToken, // does NOT match newerClaimToken
                "RETRY_SCHEDULED",
                "STALE_LEASE_RECOVERED",
                "Stale attempt message",
                Instant.now()
        );

        assertThat(finalized).isFalse();

        // Verify attempt 1 was NOT modified
        com.payrail.transfer.domain.ProcessingAttemptRecord attempt = processingAttemptRepository
                .findByTransferIdAndAttempt(transferId, 1)
                .orElseThrow();

        assertThat(attempt.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(attempt.finishedAt()).isNull();
    }

    @Test
    @DisplayName("Recovery rolls back below the retry limit when stale attempt finalization fails")
    void shouldRollbackRetryRecoveryWhenAttemptFinalizationFails() throws Exception {
        UUID transferClaimToken = UUID.randomUUID();
        UUID mismatchedAttemptToken = UUID.randomUUID();
        UUID transferId = createStaleProcessingTransfer("55.00", 1, transferClaimToken, mismatchedAttemptToken);

        int historyBefore = statusHistoryRepository.findByTransferId(transferId).size();
        long outboxBefore = countOutboxRowsForTransfer(transferId);

        int recovered = processingRecoveryService.sweepStaleProcessingLeases();
        assertThat(recovered).isEqualTo(0);

        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(transfer.processingClaimToken()).isEqualTo(transferClaimToken);
        assertThat(statusHistoryRepository.findByTransferId(transferId)).hasSize(historyBefore);
        assertThat(countOutboxRowsForTransfer(transferId)).isEqualTo(outboxBefore);

        ProcessingAttemptRecord attempt = processingAttemptRepository.findByTransferIdAndAttempt(transferId, 1).orElseThrow();
        assertThat(attempt.claimToken()).isEqualTo(mismatchedAttemptToken);
        assertThat(attempt.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(attempt.finishedAt()).isNull();
    }

    @Test
    @DisplayName("Exhaustion recovery rolls back when stale attempt finalization fails")
    void shouldRollbackExhaustionRecoveryWhenAttemptFinalizationFails() throws Exception {
        UUID transferClaimToken = UUID.randomUUID();
        UUID mismatchedAttemptToken = UUID.randomUUID();
        UUID transferId = createStaleProcessingTransfer("65.00", 5, transferClaimToken, mismatchedAttemptToken);

        int historyBefore = statusHistoryRepository.findByTransferId(transferId).size();
        long outboxBefore = countOutboxRowsForTransfer(transferId);

        int recovered = processingRecoveryService.sweepStaleProcessingLeases();
        assertThat(recovered).isEqualTo(0);

        TransferRecord transfer = transferRepository.findById(transferId).orElseThrow();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(transfer.processingClaimToken()).isEqualTo(transferClaimToken);
        assertThat(transfer.processingAttemptCount()).isEqualTo(5);
        assertThat(statusHistoryRepository.findByTransferId(transferId)).hasSize(historyBefore);
        assertThat(countOutboxRowsForTransfer(transferId)).isEqualTo(outboxBefore);

        ProcessingAttemptRecord attempt = processingAttemptRepository.findByTransferIdAndAttempt(transferId, 5).orElseThrow();
        assertThat(attempt.claimToken()).isEqualTo(mismatchedAttemptToken);
        assertThat(attempt.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(attempt.finishedAt()).isNull();
    }

    @Test
    @DisplayName("Inconsistent stale candidate failure is isolated and does not block other candidates or sweep categories")
    void shouldIsolateCandidateFailuresAndContinueSweepAcrossCategories() throws Exception {
        // Candidate 1: Inconsistent attempt claim token (bad candidate)
        UUID badTransferClaimToken = UUID.randomUUID();
        UUID mismatchedAttemptToken = UUID.randomUUID();
        UUID badTransferId = createStaleProcessingTransfer("50.00", 1, badTransferClaimToken, mismatchedAttemptToken);
        int historyBefore1 = statusHistoryRepository.findByTransferId(badTransferId).size();
        long outboxBefore1 = countOutboxRowsForTransfer(badTransferId);

        // Candidate 2: Valid matching attempt claim token (good candidate)
        UUID goodTransferClaimToken = UUID.randomUUID();
        UUID goodTransferId = createStaleProcessingTransfer("70.00", 1, goodTransferClaimToken, goodTransferClaimToken);
        int historyBefore2 = statusHistoryRepository.findByTransferId(goodTransferId).size();
        long outboxBefore2 = countOutboxRowsForTransfer(goodTransferId);

        // Candidate 3: Another recovery category (stale pending transfer with missing outbox event)
        String pendingKey = "key-stale-pending-isolated-" + UUID.randomUUID();
        CreateTransferRequest pendingReq = new CreateTransferRequest(accountAId, accountBId, "30.00", "INR");
        ResponseEntity<TransferResponse> pendingResp = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(pendingReq, headersWith(pendingKey)),
                TransferResponse.class
        );
        UUID pendingTransferId = pendingResp.getBody().id();
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id = ?", pendingTransferId);
        jdbcTemplate.update("UPDATE transfers SET created_at = now() - INTERVAL '10 minutes' WHERE id = ?", pendingTransferId);

        // Run full recovery sweep across all categories
        int totalRecovered = processingRecoveryService.runRecoverySweep();
        assertThat(totalRecovered).isGreaterThanOrEqualTo(2);

        // 1. Verify Candidate 1 was rolled back completely and remains unchanged
        TransferRecord badTransfer = transferRepository.findById(badTransferId).orElseThrow();
        assertThat(badTransfer.status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(badTransfer.processingClaimToken()).isEqualTo(badTransferClaimToken);
        assertThat(statusHistoryRepository.findByTransferId(badTransferId)).hasSize(historyBefore1);
        assertThat(countOutboxRowsForTransfer(badTransferId)).isEqualTo(outboxBefore1);

        ProcessingAttemptRecord badAttempt = processingAttemptRepository.findByTransferIdAndAttempt(badTransferId, 1).orElseThrow();
        assertThat(badAttempt.claimToken()).isEqualTo(mismatchedAttemptToken);
        assertThat(badAttempt.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(badAttempt.finishedAt()).isNull();

        // 2. Verify Candidate 2 was successfully recovered
        TransferRecord goodTransfer = transferRepository.findById(goodTransferId).orElseThrow();
        assertThat(goodTransfer.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(goodTransfer.processingClaimToken()).isNull();
        assertThat(goodTransfer.processingLeaseUntil()).isNull();
        assertThat(statusHistoryRepository.findByTransferId(goodTransferId)).hasSize(historyBefore2 + 1);
        assertThat(countOutboxRowsForTransfer(goodTransferId)).isEqualTo(outboxBefore2 + 1);

        ProcessingAttemptRecord goodAttempt = processingAttemptRepository.findByTransferIdAndAttempt(goodTransferId, 1).orElseThrow();
        assertThat(goodAttempt.outcome()).isEqualTo("RETRY_SCHEDULED");
        assertThat(goodAttempt.finishedAt()).isNotNull();

        // 3. Verify Candidate 3 from another category was also recovered by runRecoverySweep
        TransferRecord recoveredPending = transferRepository.findById(pendingTransferId).orElseThrow();
        assertThat(recoveredPending.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(countOutboxRowsForTransfer(pendingTransferId)).isGreaterThanOrEqualTo(1);
    }

    private UUID createStaleProcessingTransfer(
            String amount,
            int attemptNumber,
            UUID transferClaimToken,
            UUID attemptClaimToken
    ) throws Exception {
        String idempotencyKey = "key-stale-finalization-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountAId, accountBId, amount, "INR");
        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headersWith(idempotencyKey)),
                TransferResponse.class
        );
        UUID transferId = response.getBody().id();
        Instant startedAt = Instant.now().minusSeconds(120);

        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = ?,
                    processing_lease_until = now() - INTERVAL '10 seconds',
                    processing_attempt_count = ?
                WHERE id = ?
                """, transferClaimToken, attemptNumber, transferId);

        processingAttemptRepository.insert(new ProcessingAttemptRecord(
                UUID.randomUUID(),
                transferId,
                attemptNumber,
                attemptClaimToken,
                "IN_PROGRESS",
                null,
                null,
                startedAt,
                null,
                null
        ));
        return transferId;
    }

    private long countOutboxRowsForTransfer(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? OR correlation_id = ?",
                Integer.class,
                transferId,
                transferId
        );
    }

    private HttpHeaders headersWith(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        return headers;
    }
}
