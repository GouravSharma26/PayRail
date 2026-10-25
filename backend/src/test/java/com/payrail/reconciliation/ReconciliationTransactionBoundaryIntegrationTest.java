package com.payrail.reconciliation;

import com.payrail.BaseIntegrationTest;
import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import com.payrail.common.exception.ReconciliationConflictException;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import com.payrail.reconciliation.repository.ReconciliationIssueRepository;
import com.payrail.reconciliation.repository.ReconciliationRunRepository;
import com.payrail.reconciliation.service.ReconciliationAuditExecutor;
import com.payrail.reconciliation.service.ReconciliationCoordinator;
import com.payrail.reconciliation.service.ReconciliationFailureInjector;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationTransactionBoundaryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReconciliationCoordinator coordinator;

    @Autowired
    private ReconciliationAuditExecutor auditExecutor;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationIssueRepository issueRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        executorService = Executors.newFixedThreadPool(4);
        auditExecutor.setFailureInjector(ReconciliationFailureInjector.noop());
        coordinator.setClock(Clock.systemUTC());
        coordinator.setLastAuditFinishedAt(Instant.EPOCH);
    }

    @AfterEach
    void tearDown() {
        auditExecutor.setFailureInjector(ReconciliationFailureInjector.noop());
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("Manual trigger rolls back reconciliation run and issues when failure occurs after run is inserted")
    void shouldRollbackRunAndIssuesOnManualTriggerFailure() {
        double initialRunsMetric = getCounterCount("payrail.reconciliation.runs_completed");

        // Inject simulated database failure after run persistence
        auditExecutor.setFailureInjector((run, issues) -> {
            throw new DataIntegrityViolationException("Simulated database failure during audit issue persistence");
        });

        // Trigger manual audit through coordinator
        assertThatThrownBy(() -> coordinator.triggerManualAudit())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Simulated database failure");

        // Verify zero reconciliation runs and zero issues exist in PostgreSQL
        Integer runCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Integer.class);
        Integer issueCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_issues", Integer.class);

        assertThat(runCount).isEqualTo(0);
        assertThat(issueCount).isEqualTo(0);

        // Verify committed metric counter did NOT increment
        double runsMetricAfterRollback = getCounterCount("payrail.reconciliation.runs_completed");
        assertThat(runsMetricAfterRollback).isEqualTo(initialRunsMetric);

        // Verify lock is released and subsequent clean audit can succeed
        auditExecutor.setFailureInjector(ReconciliationFailureInjector.noop());
        coordinator.setLastAuditFinishedAt(Instant.EPOCH);

        ReconciliationRunRecord cleanRun = coordinator.triggerManualAudit();
        assertThat(cleanRun).isNotNull();
        assertThat(cleanRun.status()).isEqualTo("CLEAN");

        Integer runsAfterCommit = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Integer.class);
        assertThat(runsAfterCommit).isEqualTo(1);
    }

    @Test
    @DisplayName("Scheduled audit rolls back reconciliation run and issues when failure occurs after run is inserted")
    void shouldRollbackRunAndIssuesOnScheduledAuditFailure() {
        double initialRunsMetric = getCounterCount("payrail.reconciliation.runs_completed");

        // Inject failure after run persistence
        auditExecutor.setFailureInjector((run, issues) -> {
            throw new DataIntegrityViolationException("Simulated failure in scheduled audit");
        });

        // triggerScheduledAudit throws on failure
        assertThatThrownBy(() -> coordinator.triggerScheduledAudit())
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify neither run nor issue is committed
        Integer runCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Integer.class);
        Integer issueCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_issues", Integer.class);

        assertThat(runCount).isEqualTo(0);
        assertThat(issueCount).isEqualTo(0);

        // Verify committed metric counter did not increment
        double runsMetricAfterRollback = getCounterCount("payrail.reconciliation.runs_completed");
        assertThat(runsMetricAfterRollback).isEqualTo(initialRunsMetric);

        // Also verify scheduledAudit() method handles exception cleanly and does not leave partial records
        coordinator.setLastAuditFinishedAt(Instant.EPOCH);
        coordinator.scheduledAudit();

        runCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Integer.class);
        assertThat(runCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Successful audit commits run record and all detected issues atomically")
    void shouldCommitRunAndAllIssuesAtomicallyOnSuccess() {
        double initialRunsMetric = getCounterCount("payrail.reconciliation.runs_completed");

        // Create an account with balance discrepancy using triggers disabled fixture
        UUID accountId = UUID.randomUUID();
        withTriggersDisabled(() -> {
            jdbcTemplate.update("""
                    INSERT INTO accounts (id, display_name, currency, opening_balance, current_balance, status, created_at, updated_at)
                    VALUES (?, 'Corrupt Account', 'INR', 100.00, 200.00, 'ACTIVE', NOW(), NOW())
                    """, accountId);
        });

        // Trigger manual audit
        ReconciliationRunRecord run = coordinator.triggerManualAudit();

        assertThat(run).isNotNull();
        assertThat(run.status()).isEqualTo("DISCREPANCIES_DETECTED");
        assertThat(run.issueCount()).isGreaterThanOrEqualTo(1);

        // Verify run record is committed in database
        Integer runCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_runs WHERE id = ?", Integer.class, run.id());
        assertThat(runCount).isEqualTo(1);

        // Verify issues are committed in database with matching run_id
        Integer issueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE run_id = ?", Integer.class, run.id());
        assertThat(issueCount).isEqualTo(run.issueCount());

        // Verify committed counter incremented by exactly 1
        double runsMetricAfterCommit = getCounterCount("payrail.reconciliation.runs_completed");
        assertThat(runsMetricAfterCommit).isEqualTo(initialRunsMetric + 1.0);
    }

    @Test
    @DisplayName("Manual and scheduled execution cannot overlap, including while transaction is finishing")
    void shouldPreventManualAndScheduledExecutionOverlap() throws Exception {
        CountDownLatch inTransactionLatch = new CountDownLatch(1);
        CountDownLatch finishTransactionLatch = new CountDownLatch(1);

        // Pause inside transaction after run persistence
        auditExecutor.setFailureInjector((run, issues) -> {
            inTransactionLatch.countDown();
            try {
                finishTransactionLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 1 initiates manual audit
        Future<ReconciliationRunRecord> thread1Future = executorService.submit(() -> coordinator.triggerManualAudit());

        // Wait until Thread 1 is inside the transaction
        assertThat(inTransactionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.isAuditRunning()).isTrue();

        // While Thread 1 transaction is active, attempt concurrent manual trigger from Thread 2
        AtomicBoolean manualConflictThrown = new AtomicBoolean(false);
        Future<?> thread2Future = executorService.submit(() -> {
            try {
                coordinator.triggerManualAudit();
            } catch (ReconciliationConflictException e) {
                manualConflictThrown.set(true);
            }
        });
        thread2Future.get(5, TimeUnit.SECONDS);
        assertThat(manualConflictThrown.get()).isTrue();

        // While Thread 1 transaction is active, attempt concurrent scheduled trigger from Thread 3
        Future<ReconciliationRunRecord> thread3Future = executorService.submit(() -> coordinator.triggerScheduledAudit());
        ReconciliationRunRecord skippedScheduledRun = thread3Future.get(5, TimeUnit.SECONDS);
        assertThat(skippedScheduledRun).isNull(); // Cleanly skipped

        // Release Thread 1 transaction
        finishTransactionLatch.countDown();
        ReconciliationRunRecord completedRun = thread1Future.get(5, TimeUnit.SECONDS);

        assertThat(completedRun).isNotNull();
        assertThat(coordinator.isAuditRunning()).isFalse();

        // Only 1 reconciliation run committed
        Integer runCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Integer.class);
        assertThat(runCount).isEqualTo(1);
    }

    private double getCounterCount(String counterName) {
        var meter = meterRegistry.find(counterName).counter();
        return meter != null ? meter.count() : 0.0;
    }
}
