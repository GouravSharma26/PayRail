package com.payrail.ops;

import com.payrail.BaseIntegrationTest;
import com.payrail.ops.dto.OutboxStatusResponse;
import com.payrail.ops.dto.ReconciliationRunDetailResponse;
import com.payrail.ops.dto.SystemHealthSummaryResponse;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpsControllerIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET /api/v1/ops/reconciliation/runs returns empty list when no runs have executed")
    void testListReconciliationRunsEmpty() {
        ResponseEntity<List<ReconciliationRunRecord>> response = restTemplate.exchange(
                "/api/v1/ops/reconciliation/runs",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.payrail.ledger.service.ReconciliationService reconciliationService;

    @Test
    @DisplayName("POST /api/v1/ops/reconciliation/trigger executes audit and GET detail returns run and issues")
    void testTriggerReconciliationAndFetchRun() {
        // Reset cooldown before test
        reconciliationService.setLastAuditFinishedAt(Instant.EPOCH);

        // Trigger manual audit
        ResponseEntity<ReconciliationRunRecord> triggerResponse = restTemplate.postForEntity(
                "/api/v1/ops/reconciliation/trigger",
                null,
                ReconciliationRunRecord.class
        );

        assertThat(triggerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReconciliationRunRecord run = triggerResponse.getBody();
        assertThat(run).isNotNull();
        assertThat(run.status()).isEqualTo("CLEAN");
        assertThat(run.issueCount()).isEqualTo(0);
        assertThat(run.triggerType()).isEqualTo("MANUAL_API_TRIGGER");

        // Fetch detail by ID
        ResponseEntity<ReconciliationRunDetailResponse> detailResponse = restTemplate.getForEntity(
                "/api/v1/ops/reconciliation/runs/" + run.id(),
                ReconciliationRunDetailResponse.class
        );

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReconciliationRunDetailResponse detail = detailResponse.getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.run().id()).isEqualTo(run.id());
        assertThat(detail.issues()).isEmpty();

        // List runs returns this run
        ResponseEntity<List<ReconciliationRunRecord>> listResponse = restTemplate.exchange(
                "/api/v1/ops/reconciliation/runs?limit=10",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);
        assertThat(listResponse.getBody().get(0).id()).isEqualTo(run.id());
    }

    @Test
    @DisplayName("Immediate subsequent manual trigger is rejected with 429 and Retry-After header on cooldown")
    void testTriggerReconciliationCooldownRejected() {
        // Reset cooldown
        reconciliationService.setLastAuditFinishedAt(Instant.EPOCH);

        // 1. First trigger succeeds
        ResponseEntity<ReconciliationRunRecord> first = restTemplate.postForEntity(
                "/api/v1/ops/reconciliation/trigger",
                null,
                ReconciliationRunRecord.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Immediate second trigger fails with 429
        ResponseEntity<com.payrail.common.model.ErrorResponse> second = restTemplate.postForEntity(
                "/api/v1/ops/reconciliation/trigger",
                null,
                com.payrail.common.model.ErrorResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().code()).isEqualTo("RECONCILIATION_COOLDOWN_ACTIVE");
        assertThat(second.getHeaders().getFirst("Retry-After")).isNotNull();

        // 3. Resetting cooldown allows triggering again
        reconciliationService.setLastAuditFinishedAt(Instant.EPOCH);
        ResponseEntity<ReconciliationRunRecord> third = restTemplate.postForEntity(
                "/api/v1/ops/reconciliation/trigger",
                null,
                ReconciliationRunRecord.class
        );
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Concurrent manual trigger requests are guarded against overlapping execution")
    void testConcurrentTriggerSingleFlightGuard() throws Exception {
        reconciliationService.setLastAuditFinishedAt(Instant.EPOCH);

        int threads = 6;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                ResponseEntity<String> resp = restTemplate.postForEntity(
                        "/api/v1/ops/reconciliation/trigger",
                        null,
                        String.class
                );
                return resp.getStatusCode().value();
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        int okCount = 0;
        int conflictOrRateLimitCount = 0;
        for (java.util.concurrent.Future<Integer> f : futures) {
            int code = f.get();
            if (code == 200) {
                okCount++;
            } else if (code == 409 || code == 429) {
                conflictOrRateLimitCount++;
            }
        }
        executor.shutdown();

        assertThat(okCount).isEqualTo(1);
        assertThat(conflictOrRateLimitCount).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("GET /api/v1/ops/reconciliation/runs/{runId} returns 404 for unknown UUID")
    void testGetReconciliationRunDetailNotFound() {
        ResponseEntity<ReconciliationRunDetailResponse> response = restTemplate.getForEntity(
                "/api/v1/ops/reconciliation/runs/" + UUID.randomUUID(),
                ReconciliationRunDetailResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/v1/ops/outbox/status returns backlog telemetry")
    void testGetOutboxStatus() {
        // Insert a dummy outbox event
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, aggregate_sequence, event_type, schema_version, payload, created_at, correlation_id)
                VALUES (?, 'transfer', ?, 1, 'transfer.requested', 1, '{"transferId":"%s"}'::jsonb, NOW(), ?)
                """.formatted(transferId),
                eventId, transferId, UUID.randomUUID());

        ResponseEntity<OutboxStatusResponse> response = restTemplate.getForEntity(
                "/api/v1/ops/outbox/status",
                OutboxStatusResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        OutboxStatusResponse status = response.getBody();
        assertThat(status).isNotNull();
        assertThat(status.totalUnpublished()).isGreaterThanOrEqualTo(1);
        assertThat(status.pendingCount()).isGreaterThanOrEqualTo(1);
        assertThat(status.oldestUnpublishedAgeSeconds()).isNotNull();
    }

    @Test
    @DisplayName("GET /api/v1/ops/health/summary returns combined operational health")
    void testGetHealthSummary() {
        ResponseEntity<SystemHealthSummaryResponse> response = restTemplate.getForEntity(
                "/api/v1/ops/health/summary",
                SystemHealthSummaryResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SystemHealthSummaryResponse health = response.getBody();
        assertThat(health).isNotNull();
        assertThat(health.status()).isIn("UP", "DEGRADED");
        assertThat(health.dbStatus()).isEqualTo("UP");
        assertThat(health.kafkaStatus()).isEqualTo("UP");
        assertThat(health.timestamp()).isNotNull();
    }
}
