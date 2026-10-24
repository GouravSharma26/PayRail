package com.payrail.metrics;

import com.payrail.BaseIntegrationTest;
import com.payrail.config.PayRailMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Autowired
    private PayRailMetrics payRailMetrics;

    @Test
    @DisplayName("GET /actuator/prometheus returns Prometheus scrape format with PayRail metrics")
    void testPrometheusEndpointContainsPayRailMetrics() {
        // Record some metric updates
        payRailMetrics.recordTransferSubmitted();
        payRailMetrics.recordIdempotentReplay();
        payRailMetrics.recordTransferSucceeded();
        payRailMetrics.recordConsumerEventReceived();
        payRailMetrics.recordRecoverySweepRun();

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();

        // Verify key custom PayRail metrics are exported
        assertThat(body).contains("payrail_transfers_submitted_total");
        assertThat(body).contains("payrail_transfers_idempotent_replay_total");
        assertThat(body).contains("payrail_transfers_succeeded_total");
        assertThat(body).contains("payrail_consumer_events_received_total");
        assertThat(body).contains("payrail_recovery_sweeps_run_total");
        assertThat(body).contains("payrail_outbox_pending_count");
    }
}
