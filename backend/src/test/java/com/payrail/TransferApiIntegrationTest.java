package com.payrail;

import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.common.model.ErrorResponse;
import com.payrail.common.model.PagedResponse;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.ProcessingAttemptResponse;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.dto.TransferStatusHistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiIntegrationTest extends BaseIntegrationTest {

    private UUID accountAId;
    private UUID accountBId;
    private UUID accountUsdId;

    @BeforeEach
    void setupAccounts() {
        AccountResponse accA = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Account A", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accA).isNotNull();
        accountAId = accA.id();

        AccountResponse accB = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Account B", "INR", "500.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accB).isNotNull();
        accountBId = accB.id();

        AccountResponse accUsd = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Account USD", "USD", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(accUsd).isNotNull();
        accountUsdId = accUsd.id();
    }

    @Test
    @DisplayName("Should submit transfer with 202 Accepted, PENDING status, and atomic outbox/idempotency persistence")
    void shouldSubmitTransferSuccessfully() {
        String idempotencyKey = "key-test-transfer-001";
        CreateTransferRequest request = new CreateTransferRequest(
                accountAId,
                accountBId,
                "125.40",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Correlation-Id", "11111111-1111-1111-1111-111111111111");

        ResponseEntity<TransferResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TransferResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        TransferResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(body.amount()).isEqualTo("125.40");
        assertThat(body.currency()).isEqualTo("INR");
        assertThat(body.correlationId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        // Verify Database Invariants
        // 1. Transfer row persisted with PENDING
        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfers WHERE id = ? AND status = 'PENDING'",
                Integer.class,
                body.id()
        );
        assertThat(transferCount).isEqualTo(1);

        // 2. Idempotency record persisted with canonical request hash
        Integer idempotencyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ? AND transfer_id = ?",
                Integer.class,
                idempotencyKey,
                body.id()
        );
        assertThat(idempotencyCount).isEqualTo(1);

        // 3. Initial transfer status history persisted
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer_status_history WHERE transfer_id = ? AND to_status = 'PENDING'",
                Integer.class,
                body.id()
        );
        assertThat(historyCount).isEqualTo(1);

        // 4. Outbox event persisted in same transaction
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'transfer.requested'",
                Integer.class,
                body.id()
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return original transfer with 200 OK on idempotent replay with same key and payload")
    void shouldReplayIdempotentTransferWithSameKey() {
        String idempotencyKey = "key-test-transfer-replay-001";
        CreateTransferRequest request = new CreateTransferRequest(
                accountAId,
                accountBId,
                "50.00",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);

        // First submission -> 202 Accepted
        ResponseEntity<TransferResponse> firstResponse = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TransferResponse.class
        );
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID transferId = firstResponse.getBody().id();

        // Second submission with identical key and payload -> 200 OK with original transfer
        ResponseEntity<TransferResponse> replayResponse = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TransferResponse.class
        );
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayResponse.getBody()).isNotNull();
        assertThat(replayResponse.getBody().id()).isEqualTo(transferId);

        // Invariant: exactly one transfer row and one outbox event exist
        Integer totalTransfers = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class);
        assertThat(totalTransfers).isEqualTo(1);

        Integer totalOutbox = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(totalOutbox).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return 409 IDEMPOTENCY_KEY_REUSED when reusing key with different request payload")
    void shouldReturnConflictOnKeyReuseWithDifferentPayload() {
        String idempotencyKey = "key-test-transfer-conflict-001";
        CreateTransferRequest originalRequest = new CreateTransferRequest(
                accountAId,
                accountBId,
                "50.00",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);

        // 1. Initial submission
        ResponseEntity<TransferResponse> firstResponse = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(originalRequest, headers),
                TransferResponse.class
        );
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 2. Different payload with SAME key (different amount: 75.00 vs 50.00)
        CreateTransferRequest differentRequest = new CreateTransferRequest(
                accountAId,
                accountBId,
                "75.00",
                "INR"
        );

        ResponseEntity<ErrorResponse> conflictResponse = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(differentRequest, headers),
                ErrorResponse.class
        );

        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResponse.getBody()).isNotNull();
        assertThat(conflictResponse.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("Should reject transfer when Idempotency-Key header is missing")
    void shouldRejectMissingIdempotencyKey() {
        CreateTransferRequest request = new CreateTransferRequest(
                accountAId,
                accountBId,
                "50.00",
                "INR"
        );

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request), // No header
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Should reject transfer when source and destination accounts are the same")
    void shouldRejectSameSourceAndDestination() {
        CreateTransferRequest request = new CreateTransferRequest(
                accountAId,
                accountAId, // Same account
                "50.00",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-same-account-test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_TRANSFER");
    }

    @Test
    @DisplayName("Should reject transfer on currency mismatch between accounts or transfer")
    void shouldRejectCurrencyMismatch() {
        CreateTransferRequest request = new CreateTransferRequest(
                accountAId, // INR
                accountUsdId, // USD
                "50.00",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-currency-mismatch-test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("Should return 404 RESOURCE_NOT_FOUND when source or destination account does not exist")
    void shouldReturn404ForNonExistentAccount() {
        UUID unknownId = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(
                unknownId,
                accountBId,
                "50.00",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-unknown-account-test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("Should search transfers by criteria and retrieve status history and attempts")
    void shouldSearchTransfersAndRetrieveHistory() {
        String key = "key-search-history-001";
        CreateTransferRequest request = new CreateTransferRequest(
                accountAId,
                accountBId,
                "80.00",
                "INR"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);

        ResponseEntity<TransferResponse> created = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                TransferResponse.class
        );
        UUID transferId = created.getBody().id();

        // 1. Search by status PENDING
        ParameterizedTypeReference<PagedResponse<TransferResponse>> pagedType = new ParameterizedTypeReference<>() {};
        ResponseEntity<PagedResponse<TransferResponse>> searchResponse = restTemplate.exchange(
                "/api/v1/transfers?status=PENDING&page=0&size=10",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                pagedType
        );
        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(searchResponse.getBody().items()).hasSize(1);
        assertThat(searchResponse.getBody().items().get(0).id()).isEqualTo(transferId);

        // 2. Get status history
        ParameterizedTypeReference<List<TransferStatusHistoryResponse>> historyType = new ParameterizedTypeReference<>() {};
        ResponseEntity<List<TransferStatusHistoryResponse>> historyResponse = restTemplate.exchange(
                "/api/v1/transfers/" + transferId + "/history",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                historyType
        );
        assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyResponse.getBody()).hasSize(1);
        assertThat(historyResponse.getBody().get(0).toStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(historyResponse.getBody().get(0).reasonCode()).isEqualTo("TRANSFER_SUBMITTED");

        // 3. Get attempts (empty in Phase 1)
        ParameterizedTypeReference<List<ProcessingAttemptResponse>> attemptsType = new ParameterizedTypeReference<>() {};
        ResponseEntity<List<ProcessingAttemptResponse>> attemptsResponse = restTemplate.exchange(
                "/api/v1/transfers/" + transferId + "/attempts",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                attemptsType
        );
        assertThat(attemptsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attemptsResponse.getBody()).isEmpty();
    }

    @Test
    @DisplayName("Gate Invariant: No transfer may be marked SUCCEEDED in Phase 1")
    void noTransferMayBeMarkedSucceededInPhase1() {
        // Assert that across all created transfers in Phase 1, NONE have SUCCEEDED status
        Integer succeededCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfers WHERE status = 'SUCCEEDED'",
                Integer.class
        );
        assertThat(succeededCount).isZero();
    }
}
