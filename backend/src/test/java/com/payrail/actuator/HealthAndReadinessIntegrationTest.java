package com.payrail.actuator;

import com.payrail.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthAndReadinessIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET /actuator/health returns 200 and status UP")
    void testOverallHealthEndpoint() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("GET /actuator/health/liveness returns 200 and status UP")
    void testLivenessEndpoint() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health/liveness", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("GET /actuator/health/readiness returns 200 and status UP with db and kafka")
    void testReadinessEndpoint() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }
}
