package com.payrail.ops.service;

import com.payrail.config.KafkaHealthIndicator;
import com.payrail.config.PayRailProperties;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.ops.dto.OutboxStatusResponse;
import com.payrail.ops.dto.ReconciliationRunDetailResponse;
import com.payrail.ops.dto.SystemHealthSummaryResponse;
import com.payrail.reconciliation.domain.ReconciliationIssueRecord;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import com.payrail.reconciliation.repository.ReconciliationIssueRepository;
import com.payrail.reconciliation.repository.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationIssueRepository issueRepository;
    private final KafkaHealthIndicator kafkaHealthIndicator;
    private final PayRailProperties payRailProperties;

    public OpsService(
            JdbcTemplate jdbcTemplate,
            ReconciliationService reconciliationService,
            @Autowired(required = false) ReconciliationRunRepository runRepository,
            @Autowired(required = false) ReconciliationIssueRepository issueRepository,
            @Autowired(required = false) KafkaHealthIndicator kafkaHealthIndicator,
            PayRailProperties payRailProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.reconciliationService = reconciliationService;
        this.runRepository = runRepository;
        this.issueRepository = issueRepository;
        this.kafkaHealthIndicator = kafkaHealthIndicator;
        this.payRailProperties = payRailProperties;
    }

    public List<ReconciliationRunRecord> getRecentReconciliationRuns(int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 100);
        if (runRepository == null) {
            return Collections.emptyList();
        }
        return runRepository.findRecent(boundedLimit);
    }

    public Optional<ReconciliationRunDetailResponse> getReconciliationRunDetail(UUID runId) {
        if (runRepository == null) {
            return Optional.empty();
        }
        Optional<ReconciliationRunRecord> runOpt = runRepository.findById(runId);
        if (runOpt.isEmpty()) {
            return Optional.empty();
        }

        List<ReconciliationIssueRecord> issues = (issueRepository != null)
                ? issueRepository.findByRunId(runId)
                : Collections.emptyList();

        return Optional.of(new ReconciliationRunDetailResponse(runOpt.get(), issues));
    }

    public ReconciliationRunRecord triggerReconciliation() {
        log.info("Operational manual trigger of reconciliation audit initiated");
        return reconciliationService.triggerManualAudit();
    }

    @Transactional(readOnly = true)
    public OutboxStatusResponse getOutboxStatus() {
        Long pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND last_error_code IS NULL", Long.class);
        long pendingCount = pending != null ? pending : 0L;

        Long failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND last_error_code IS NOT NULL AND last_error_code <> 'PUBLISH_RETRY_EXHAUSTED'", Long.class);
        long failedCount = failed != null ? failed : 0L;

        Long exhausted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND last_error_code = 'PUBLISH_RETRY_EXHAUSTED'", Long.class);
        long exhaustedCount = exhausted != null ? exhausted : 0L;

        long totalUnpublished = pendingCount + failedCount + exhaustedCount;

        Timestamp oldestTs = jdbcTemplate.queryForObject(
                "SELECT MIN(created_at) FROM outbox_events WHERE published_at IS NULL", Timestamp.class);
        Long oldestAgeSeconds = oldestTs != null
                ? Duration.between(oldestTs.toInstant(), Instant.now()).toSeconds()
                : null;

        boolean publisherEnabled = payRailProperties.getOutbox().isPublisherEnabled();

        return new OutboxStatusResponse(
                pendingCount,
                failedCount,
                exhaustedCount,
                totalUnpublished,
                oldestAgeSeconds,
                publisherEnabled
        );
    }

    public SystemHealthSummaryResponse getHealthSummary() {
        Instant now = Instant.now();

        // 1. Check DB
        String dbStatus = "UP";
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            log.warn("Database health check probe failed: {}", e.getMessage());
            dbStatus = "DOWN";
        }

        // 2. Check Kafka
        String kafkaStatus = "UNKNOWN";
        if (kafkaHealthIndicator != null) {
            try {
                Health health = kafkaHealthIndicator.health();
                kafkaStatus = Status.UP.equals(health.getStatus()) ? "UP" : "DOWN";
            } catch (Exception e) {
                log.warn("Kafka health check probe failed: {}", e.getMessage());
                kafkaStatus = "DOWN";
            }
        }

        // 3. Outbox backlog
        Long outboxBacklog = null;
        if ("UP".equals(dbStatus)) {
            try {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
                outboxBacklog = count != null ? count : 0L;
            } catch (Exception e) {
                log.warn("Failed to query outbox backlog for health summary: {}", e.getMessage());
            }
        }

        // 4. Last reconciliation run and open issues
        String lastReconStatus = "UNKNOWN";
        Instant lastReconTime = null;
        Long openIssues = null;

        if ("UP".equals(dbStatus)) {
            if (runRepository != null) {
                try {
                    List<ReconciliationRunRecord> recent = runRepository.findRecent(1);
                    if (!recent.isEmpty()) {
                        ReconciliationRunRecord latest = recent.get(0);
                        lastReconStatus = latest.status();
                        lastReconTime = latest.finishedAt();
                    } else {
                        lastReconStatus = "NONE";
                    }
                } catch (Exception e) {
                    log.warn("Failed to query recent reconciliation runs: {}", e.getMessage());
                    lastReconStatus = "UNKNOWN";
                }
            } else {
                lastReconStatus = "NONE";
            }

            if (issueRepository != null) {
                try {
                    openIssues = issueRepository.countByStatus("OPEN");
                } catch (Exception e) {
                    log.warn("Failed to query open reconciliation issues count: {}", e.getMessage());
                    openIssues = null;
                }
            } else {
                openIssues = 0L;
            }
        }

        // Determine overall status
        String overallStatus = "UP";
        if ("DOWN".equals(dbStatus) || "DOWN".equals(kafkaStatus)) {
            overallStatus = "DOWN";
        } else if ("UNKNOWN".equals(lastReconStatus)
                || openIssues == null
                || outboxBacklog == null
                || openIssues > 0
                || outboxBacklog > 500
                || "DISCREPANCIES_DETECTED".equals(lastReconStatus)
                || "UNKNOWN".equals(kafkaStatus)) {
            overallStatus = "DEGRADED";
        }

        return new SystemHealthSummaryResponse(
                overallStatus,
                dbStatus,
                kafkaStatus,
                outboxBacklog,
                lastReconStatus,
                lastReconTime,
                openIssues,
                now
        );
    }
}
