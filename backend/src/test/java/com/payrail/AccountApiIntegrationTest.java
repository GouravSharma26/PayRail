package com.payrail;

import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.common.model.ErrorResponse;
import com.payrail.common.model.PagedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountApiIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should create synthetic account with 201 Created and return decimal strings")
    void shouldCreateAccountSuccessfully() {
        CreateAccountRequest request = new CreateAccountRequest(
                "Test Account Alpha",
                "INR",
                "1000.50"
        );

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts",
                request,
                AccountResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().displayName()).isEqualTo("Test Account Alpha");
        assertThat(response.getBody().currency()).isEqualTo("INR");
        assertThat(response.getBody().openingBalance()).isEqualTo("1000.50");
        assertThat(response.getBody().currentBalance()).isEqualTo("1000.50");
        assertThat(response.getBody().status().name()).isEqualTo("ACTIVE");
        assertThat(response.getBody().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Should reject account creation when opening balance has excess decimal places")
    void shouldRejectExcessOpeningBalanceScale() {
        CreateAccountRequest request = new CreateAccountRequest(
                "Test Account Bad Scale",
                "INR",
                "1000.125"
        );

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts",
                request,
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().correlationId()).isNotNull();
    }

    @Test
    @DisplayName("Should reject account creation when currency format is invalid")
    void shouldRejectInvalidCurrencyFormat() {
        CreateAccountRequest request = new CreateAccountRequest(
                "Test Account Bad Currency",
                "inr_extra",
                "500.00"
        );

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts",
                request,
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Should fetch account details by ID with 200 OK")
    void shouldFetchAccountById() {
        CreateAccountRequest request = new CreateAccountRequest(
                "Account To Fetch",
                "EUR",
                "750.00"
        );
        AccountResponse created = restTemplate.postForEntity("/api/v1/accounts", request, AccountResponse.class).getBody();
        assertThat(created).isNotNull();

        ResponseEntity<AccountResponse> response = restTemplate.getForEntity(
                "/api/v1/accounts/" + created.id(),
                AccountResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(created.id());
        assertThat(response.getBody().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("Should return 404 RESOURCE_NOT_FOUND when querying unknown account")
    void shouldReturn404ForUnknownAccount() {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/v1/accounts/" + unknownId,
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("Should list accounts with bounded pagination")
    void shouldListAccountsWithPagination() {
        // Create 3 accounts
        restTemplate.postForEntity("/api/v1/accounts", new CreateAccountRequest("Acc 1", "INR", "100.00"), AccountResponse.class);
        restTemplate.postForEntity("/api/v1/accounts", new CreateAccountRequest("Acc 2", "INR", "200.00"), AccountResponse.class);
        restTemplate.postForEntity("/api/v1/accounts", new CreateAccountRequest("Acc 3", "USD", "300.00"), AccountResponse.class);

        ParameterizedTypeReference<PagedResponse<AccountResponse>> pagedType = new ParameterizedTypeReference<>() {};

        ResponseEntity<PagedResponse<AccountResponse>> response = restTemplate.exchange(
                "/api/v1/accounts?currency=INR&page=0&size=10",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                pagedType
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalItems()).isEqualTo(2);
        assertThat(response.getBody().items()).hasSize(2);
    }
}
