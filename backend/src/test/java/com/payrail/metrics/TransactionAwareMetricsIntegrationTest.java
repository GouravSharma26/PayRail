package com.payrail.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.BaseIntegrationTest;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.account.repository.AccountRepository;
import com.payrail.consumer.service.AsyncTransferProcessor;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.outbox.domain.EventEnvelope;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.service.TransferSubmissionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionAwareMetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransferSubmissionService transferSubmissionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AsyncTransferProcessor asyncTransferProcessor;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        asyncTransferProcessor.setFailureInjector(null);
    }

    @Test
    @DisplayName("Rolled-back transfer submission does not increment payrail.transfers.submitted counter")
    void shouldNotIncrementSubmittedCounterOnRollback() {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "500.00");
        createAccount(dstId, "500.00");

        double initialSubmittedCount = getCounterCount("payrail.transfers.submitted");

        CreateTransferRequest request = new CreateTransferRequest(
                srcId,
                dstId,
                "50.00",
                "INR"
        );
        String idempotencyKey = UUID.randomUUID().toString();

        // Execute submission in a transaction that subsequently rolls back
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            transferSubmissionService.submitTransfer(idempotencyKey, request);
            throw new RuntimeException("Simulated transaction failure after submission before commit");
        })).hasMessageContaining("Simulated transaction failure");

        // Verify the counter did NOT increment
        double countAfterRollback = getCounterCount("payrail.transfers.submitted");
        assertThat(countAfterRollback).isEqualTo(initialSubmittedCount);

        // Now submit successfully outside a failing transaction
        transferSubmissionService.submitTransfer(idempotencyKey, request);

        // Verify the counter now incremented by exactly 1
        double countAfterCommit = getCounterCount("payrail.transfers.submitted");
        assertThat(countAfterCommit).isEqualTo(initialSubmittedCount + 1.0);
    }

    @Test
    @DisplayName("Rolled-back transfer posting does not increment payrail.transfers.succeeded counter")
    void shouldNotIncrementSucceededCounterOnPostingRollback() throws Exception {
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        createAccount(srcId, "500.00");
        createAccount(dstId, "500.00");

        CreateTransferRequest request = new CreateTransferRequest(
                srcId,
                dstId,
                "50.00",
                "INR"
        );
        String key = UUID.randomUUID().toString();
        var submission = transferSubmissionService.submitTransfer(key, request);
        UUID transferId = submission.transfer().id();

        double initialSucceededCount = getCounterCount("payrail.transfers.succeeded");

        // Inject failure on attempt 1
        asyncTransferProcessor.setFailureInjector((transfer, attemptNumber) -> {
            if (transfer.id().equals(transferId) && attemptNumber == 1) {
                throw new RuntimeException("Simulated service failure during posting attempt 1");
            }
        });

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

        // Process attempt 1 (fails and rolls back posting transaction)
        asyncTransferProcessor.processTransferEvent(envelope);

        // Succeeded counter must NOT have incremented
        double countAfterFailure = getCounterCount("payrail.transfers.succeeded");
        assertThat(countAfterFailure).isEqualTo(initialSucceededCount);

        // Clear injector and process attempt 2 (succeeds and commits)
        asyncTransferProcessor.setFailureInjector(null);
        EventEnvelope retryEnvelope = new EventEnvelope(
                UUID.randomUUID(),
                "transfer.retry",
                1,
                Instant.now(),
                "transfer",
                transferId,
                2L,
                UUID.randomUUID(),
                objectMapper.readTree(objectMapper.writeValueAsString(Map.of("transferId", transferId.toString(), "retryAttempt", 2)))
        );

        asyncTransferProcessor.processTransferEvent(retryEnvelope);

        // Succeeded counter must now have incremented by exactly 1
        double countAfterCommit = getCounterCount("payrail.transfers.succeeded");
        assertThat(countAfterCommit).isEqualTo(initialSucceededCount + 1.0);
    }

    @Test
    @DisplayName("Rolled-back reconciliation audit does not increment payrail.reconciliation.runs_completed counter")
    void shouldNotIncrementReconciliationRunsCounterOnRollback() {
        double initialReconRuns = getCounterCount("payrail.reconciliation.runs_completed");

        // Roll back audit transaction
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            reconciliationService.runAuditAndPersist("ROLLBACK_TEST");
            throw new RuntimeException("Simulated transaction rollback during audit persistence");
        })).hasMessageContaining("Simulated transaction rollback");

        // Counter must NOT have incremented
        double countAfterRollback = getCounterCount("payrail.reconciliation.runs_completed");
        assertThat(countAfterRollback).isEqualTo(initialReconRuns);

        // Successful audit outside failing transaction
        reconciliationService.runAuditAndPersist("COMMIT_TEST");

        // Counter must increment by exactly 1
        double countAfterCommit = getCounterCount("payrail.reconciliation.runs_completed");
        assertThat(countAfterCommit).isEqualTo(initialReconRuns + 1.0);
    }

    private void createAccount(UUID id, String balance) {
        accountRepository.insert(new AccountRecord(
                id,
                "Test Account",
                "INR",
                new BigDecimal(balance),
                new BigDecimal(balance),
                AccountStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
    }

    private double getCounterCount(String counterName) {
        var meter = meterRegistry.find(counterName).counter();
        return meter != null ? meter.count() : 0.0;
    }
}
