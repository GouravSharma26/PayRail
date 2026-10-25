package com.payrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CorsIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Preflight OPTIONS request from allowed origin returns 200 and CORS headers")
    void preflightOptionsFromAllowedOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");
        headers.set("Access-Control-Request-Method", "POST");
        headers.set("Access-Control-Request-Headers", "Content-Type,Idempotency-Key");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.OPTIONS,
                new HttpEntity<>(headers),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
        assertThat(response.getHeaders().getAccessControlAllowMethods())
                .contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS);
        assertThat(response.getHeaders().getAccessControlExposeHeaders())
                .contains("Retry-After", "X-Correlation-Id");
    }

    @Test
    @DisplayName("GET request from allowed origin returns Access-Control-Allow-Origin header")
    void getRequestFromAllowedOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/accounts",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
    }

    @Test
    @DisplayName("Actuator health endpoint supports CORS from allowed origin")
    void actuatorHealthCors() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");
        headers.set("Access-Control-Request-Method", "GET");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.OPTIONS,
                new HttpEntity<>(headers),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
    }
}
