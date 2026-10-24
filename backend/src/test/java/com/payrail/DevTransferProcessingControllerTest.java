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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DevTransferProcessingControllerTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Finding 4: When manual-endpoint-enabled=true, POST /api/v1/transfers/{id}/process successfully executes synchronous posting")
    void shouldProcessTransferWhenEnabled() {
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
        headers.set("Idempotency-Key", "test-process-" + UUID.randomUUID());
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

        // 3. Invoke dev processing endpoint
        ResponseEntity<TransferResponse> processResp = restTemplate.postForEntity(
                "/api/v1/transfers/" + transfer.id() + "/process",
                null,
                TransferResponse.class
        );
        assertThat(processResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransferResponse processedTransfer = processResp.getBody();
        assertThat(processedTransfer).isNotNull();
        assertThat(processedTransfer.status()).isEqualTo(TransferStatus.SUCCEEDED);

        // 4. Verify ledger entries created
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transfer_id = ?",
                Integer.class,
                transfer.id()
        );
        assertThat(ledgerCount).isEqualTo(2);
    }
}
