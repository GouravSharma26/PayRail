package com.payrail;

import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.TransferResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "payrail.processing.manual-endpoint-enabled=false")
class DevTransferProcessingControllerDisabledIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Finding 4: When manual-endpoint-enabled=false (default production configuration), POST /api/v1/transfers/{id}/process returns 404 Not Found")
    void shouldReturn404WhenManualEndpointDisabled() {
        // 1. Create accounts
        AccountResponse sender = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Sender", "INR", "1000.00"),
                AccountResponse.class
        ).getBody();
        assertThat(sender).isNotNull();

        AccountResponse recipient = restTemplate.postForEntity(
                "/api/v1/accounts",
                new CreateAccountRequest("Recipient", "INR", "200.00"),
                AccountResponse.class
        ).getBody();
        assertThat(recipient).isNotNull();

        // 2. Submit transfer
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "test-process-disabled-" + UUID.randomUUID());
        ResponseEntity<TransferResponse> submitResp = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(new CreateTransferRequest(sender.id(), recipient.id(), "150.00", "INR"), headers),
                TransferResponse.class
        );
        assertThat(submitResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        TransferResponse transfer = submitResp.getBody();
        assertThat(transfer).isNotNull();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);

        // 3. Attempt to invoke dev processing endpoint; must return 404 because controller bean is not registered
        ResponseEntity<String> processResp = restTemplate.postForEntity(
                "/api/v1/transfers/" + transfer.id() + "/process",
                null,
                String.class
        );
        assertThat(processResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 4. Verify transfer remains PENDING and no ledger entries were created
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transfer_id = ?",
                Integer.class,
                transfer.id()
        );
        assertThat(ledgerCount).isZero();
    }
}
