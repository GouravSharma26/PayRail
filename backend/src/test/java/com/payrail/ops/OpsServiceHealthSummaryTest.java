package com.payrail.ops;

import com.payrail.config.KafkaHealthIndicator;
import com.payrail.config.PayRailProperties;
import com.payrail.ledger.service.ReconciliationService;
import com.payrail.ops.dto.SystemHealthSummaryResponse;
import com.payrail.ops.service.OpsService;
import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import com.payrail.reconciliation.repository.ReconciliationIssueRepository;
import com.payrail.reconciliation.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsServiceHealthSummaryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ReconciliationService reconciliationService;

    @Mock
    private ReconciliationRunRepository runRepository;

    @Mock
    private ReconciliationIssueRepository issueRepository;

    @Mock
    private KafkaHealthIndicator kafkaHealthIndicator;

    private PayRailProperties payRailProperties;
    private OpsService opsService;

    @BeforeEach
    void setUp() {
        payRailProperties = new PayRailProperties();
        opsService = new OpsService(
                jdbcTemplate,
                reconciliationService,
                runRepository,
                issueRepository,
                kafkaHealthIndicator,
                payRailProperties
        );
    }

    @Test
    @DisplayName("Initial database probe failure reports DB DOWN, overall DOWN, UNKNOWN reconciliation status, and null counts without throwing")
    void shouldReportDownWhenDatabaseProbeFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL connection refused"));
        when(kafkaHealthIndicator.health()).thenReturn(Health.up().build());

        SystemHealthSummaryResponse summary = opsService.getHealthSummary();

        assertThat(summary.status()).isEqualTo("DOWN");
        assertThat(summary.dbStatus()).isEqualTo("DOWN");
        assertThat(summary.kafkaStatus()).isEqualTo("UP");
        assertThat(summary.outboxBacklog()).isNull();
        assertThat(summary.lastReconciliationStatus()).isEqualTo("UNKNOWN");
        assertThat(summary.openReconciliationIssues()).isNull();
    }

    @Test
    @DisplayName("Reconciliation run lookup failure reports DEGRADED with UNKNOWN lastReconciliationStatus without throwing")
    void shouldReportDegradedWhenReconciliationRunLookupFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"), eq(Long.class)))
                .thenReturn(0L);
        when(kafkaHealthIndicator.health()).thenReturn(Health.up().build());
        when(runRepository.findRecent(anyInt()))
                .thenThrow(new DataAccessResourceFailureException("Table reconciliation_runs locked or unavailable"));
        when(issueRepository.countByStatus("OPEN")).thenReturn(0L);

        SystemHealthSummaryResponse summary = opsService.getHealthSummary();

        assertThat(summary.status()).isEqualTo("DEGRADED");
        assertThat(summary.dbStatus()).isEqualTo("UP");
        assertThat(summary.kafkaStatus()).isEqualTo("UP");
        assertThat(summary.lastReconciliationStatus()).isEqualTo("UNKNOWN");
        assertThat(summary.outboxBacklog()).isEqualTo(0L);
        assertThat(summary.openReconciliationIssues()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Issue count lookup failure reports DEGRADED with null openReconciliationIssues without throwing")
    void shouldReportDegradedWhenIssueCountLookupFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"), eq(Long.class)))
                .thenReturn(0L);
        when(kafkaHealthIndicator.health()).thenReturn(Health.up().build());
        when(runRepository.findRecent(anyInt())).thenReturn(List.of(new ReconciliationRunRecord(
                UUID.randomUUID(),
                Instant.now().minusSeconds(60),
                Instant.now(),
                "CLEAN",
                10,
                5,
                0,
                "SCHEDULED",
                "Audit clean"
        )));
        when(issueRepository.countByStatus("OPEN"))
                .thenThrow(new DataAccessResourceFailureException("Connection timed out counting issues"));

        SystemHealthSummaryResponse summary = opsService.getHealthSummary();

        assertThat(summary.status()).isEqualTo("DEGRADED");
        assertThat(summary.dbStatus()).isEqualTo("UP");
        assertThat(summary.kafkaStatus()).isEqualTo("UP");
        assertThat(summary.lastReconciliationStatus()).isEqualTo("CLEAN");
        assertThat(summary.openReconciliationIssues()).isNull();
        assertThat(summary.outboxBacklog()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Healthy system reports UP with complete details")
    void shouldReportUpWhenAllComponentsHealthy() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"), eq(Long.class)))
                .thenReturn(5L);
        when(kafkaHealthIndicator.health()).thenReturn(Health.up().build());
        when(runRepository.findRecent(anyInt())).thenReturn(List.of(new ReconciliationRunRecord(
                UUID.randomUUID(),
                Instant.now().minusSeconds(60),
                Instant.now(),
                "CLEAN",
                10,
                5,
                0,
                "SCHEDULED",
                "Audit clean"
        )));
        when(issueRepository.countByStatus("OPEN")).thenReturn(0L);

        SystemHealthSummaryResponse summary = opsService.getHealthSummary();

        assertThat(summary.status()).isEqualTo("UP");
        assertThat(summary.dbStatus()).isEqualTo("UP");
        assertThat(summary.kafkaStatus()).isEqualTo("UP");
        assertThat(summary.lastReconciliationStatus()).isEqualTo("CLEAN");
        assertThat(summary.openReconciliationIssues()).isEqualTo(0L);
        assertThat(summary.outboxBacklog()).isEqualTo(5L);
    }
}
