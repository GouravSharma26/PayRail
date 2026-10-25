package com.payrail.reconciliation.service;

import com.payrail.common.exception.ReconciliationConflictException;
import com.payrail.common.exception.ReconciliationRateLimitException;
import com.payrail.config.PayRailProperties;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Audit coordinator responsible for single-flight concurrency control, cooldown enforcement,
 * and dispatching to the transactional audit executor through its Spring-managed proxy.
 *
 * Guarantees that the single-flight lock remains held throughout the execution and completion
 * of the database transaction, preventing overlapping audits and ensuring reliable release
 * on success, rollback, and unhandled exceptions.
 */
@Service
public class ReconciliationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationCoordinator.class);

    private final ReconciliationAuditExecutor auditExecutor;
    private final PayRailProperties payRailProperties;

    private final ReentrantLock auditLock = new ReentrantLock();
    private final AtomicReference<Instant> lastAuditFinishedAt = new AtomicReference<>(Instant.EPOCH);
    private Clock clock = Clock.systemUTC();

    public ReconciliationCoordinator(
            ReconciliationAuditExecutor auditExecutor,
            @Autowired(required = false) PayRailProperties payRailProperties
    ) {
        this.auditExecutor = auditExecutor;
        this.payRailProperties = payRailProperties;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Clock getClock() {
        return this.clock;
    }

    public void setLastAuditFinishedAt(Instant instant) {
        this.lastAuditFinishedAt.set(instant);
    }

    public Instant getLastAuditFinishedAt() {
        return this.lastAuditFinishedAt.get();
    }

    public boolean isAuditRunning() {
        return this.auditLock.isLocked();
    }

    /**
     * Public manual trigger endpoint handler.
     * Enforces cooldown and single-flight lock before delegating to the transactional audit executor.
     *
     * @return the persisted reconciliation run record
     * @throws ReconciliationRateLimitException if trigger arrives within the cooldown window
     * @throws ReconciliationConflictException  if an audit is already in progress
     */
    public ReconciliationRunRecord triggerManualAudit() {
        Instant now = clock.instant();
        Instant lastFinished = lastAuditFinishedAt.get();
        Duration cooldown = (payRailProperties != null && payRailProperties.getReconciliation() != null)
                ? payRailProperties.getReconciliation().getManualTriggerCooldown()
                : Duration.ofSeconds(5);

        if (cooldown != null && !cooldown.isZero() && !cooldown.isNegative()) {
            Duration elapsed = Duration.between(lastFinished, now);
            if (elapsed.compareTo(cooldown) < 0) {
                long remainingSeconds = Math.max(1, cooldown.minus(elapsed).toSeconds() + 1);
                log.warn("Manual reconciliation trigger rejected: on cooldown (remaining={}s)", remainingSeconds);
                throw new ReconciliationRateLimitException(
                        "Reconciliation trigger on cooldown. Please wait " + remainingSeconds + " second(s).",
                        remainingSeconds
                );
            }
        }

        if (!auditLock.tryLock()) {
            log.warn("Manual reconciliation trigger rejected: audit already in progress");
            throw new ReconciliationConflictException("Reconciliation audit is already in progress.");
        }

        try {
            return auditExecutor.executeAuditAndPersist("MANUAL_API_TRIGGER");
        } finally {
            lastAuditFinishedAt.set(clock.instant());
            auditLock.unlock();
        }
    }

    /**
     * Scheduled audit trigger.
     * Skips execution cleanly if an audit is already in progress.
     *
     * @return the persisted reconciliation run record, or null if skipped
     */
    public ReconciliationRunRecord triggerScheduledAudit() {
        if (!auditLock.tryLock()) {
            log.info("Scheduled reconciliation skipped: an audit is already in progress.");
            return null;
        }
        try {
            return auditExecutor.executeAuditAndPersist("SCHEDULED");
        } finally {
            lastAuditFinishedAt.set(clock.instant());
            auditLock.unlock();
        }
    }

    /**
     * Periodic scheduled audit entry point.
     * Delegates to {@link #triggerScheduledAudit()} and catches errors so the scheduler continues running.
     */
    @Scheduled(fixedDelayString = "${payrail.reconciliation.interval:60s}")
    public void scheduledAudit() {
        try {
            triggerScheduledAudit();
        } catch (Exception e) {
            log.error("Scheduled reconciliation run failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Coordinates a generic audit execution under the single-flight lock without cooldown enforcement.
     * Intended for programmatic or test invocations.
     *
     * @param triggerType label describing the trigger source
     * @return the persisted reconciliation run record
     */
    public ReconciliationRunRecord runAudit(String triggerType) {
        if (!auditLock.tryLock()) {
            log.warn("Reconciliation audit rejected: audit already in progress");
            throw new ReconciliationConflictException("Reconciliation audit is already in progress.");
        }
        try {
            return auditExecutor.executeAuditAndPersist(triggerType);
        } finally {
            lastAuditFinishedAt.set(clock.instant());
            auditLock.unlock();
        }
    }
}
