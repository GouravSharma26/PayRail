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

    @Test
    @DisplayName("POST /api/v1/ops/reconciliation/trigger executes audit and GET detail returns run and issues")
    void testTriggerReconciliationAndFetchRun() {
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
