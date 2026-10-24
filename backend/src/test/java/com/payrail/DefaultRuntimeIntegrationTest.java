package com.payrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that when SPRING_PROFILES_ACTIVE is unset, PayRail starts in the default safe profile:
 * - Development seed data (DevDataSeeder) is NOT registered and does not run.
 * - The manual transfer posting endpoint is NOT registered and returns 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DefaultRuntimeIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = BaseIntegrationTest.postgres;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Finding 1: Default runtime does not register DevDataSeeder and does not enable seed data")
    void defaultRuntimeShouldNotEnableSeedData() {
        boolean hasSeederBean = applicationContext.containsBean("devDataSeeder");
        assertThat(hasSeederBean)
                .as("DevDataSeeder bean must NOT be registered when SPRING_PROFILES_ACTIVE is unset")
                .isFalse();
    }

    @Test
    @DisplayName("Finding 1: Default runtime does not register POST /api/v1/transfers/{id}/process (returns 404)")
    void defaultRuntimeShouldNotRegisterManualProcessingEndpoint() {
        boolean hasDevController = applicationContext.containsBean("devTransferProcessingController");
        assertThat(hasDevController)
                .as("DevTransferProcessingController bean must NOT be registered in default runtime")
                .isFalse();

        UUID randomTransferId = UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/transfers/" + randomTransferId + "/process",
                null,
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
