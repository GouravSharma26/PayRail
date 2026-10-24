package com.payrail.transfer.controller;

import com.payrail.common.model.PagedResponse;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.CreateTransferRequest;
import com.payrail.transfer.dto.ProcessingAttemptResponse;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.dto.TransferSearchCriteria;
import com.payrail.transfer.dto.TransferStatusHistoryResponse;
import com.payrail.transfer.dto.TransferSubmissionResult;
import com.payrail.transfer.service.TransferQueryService;
import com.payrail.transfer.service.TransferSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Operations for submitting and searching simulated transfers")
public class TransferController {

    private final TransferSubmissionService submissionService;
    private final TransferQueryService queryService;
    private final com.payrail.ledger.service.TransferPostingService postingService;

    public TransferController(
            TransferSubmissionService submissionService,
            TransferQueryService queryService,
            com.payrail.ledger.service.TransferPostingService postingService
    ) {
        this.submissionService = submissionService;
        this.queryService = queryService;
        this.postingService = postingService;
    }

    @PostMapping
    @Operation(
            summary = "Submit transfer command",
            description = "Submits a transfer request for asynchronous processing. Requires an Idempotency-Key header. Returns 202 Accepted on first submission and 200 OK on idempotent replays."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Transfer durably accepted for processing (status: PENDING)"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of previously accepted transfer"),
            @ApiResponse(responseCode = "400", description = "Validation error, currency mismatch, or inactive account"),
            @ApiResponse(responseCode = "404", description = "Source or destination account not found"),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different request payload")
    })
    public ResponseEntity<TransferResponse> createTransfer(
            @Parameter(
                    name = "Idempotency-Key",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Client-supplied idempotency key (8-128 ASCII printable characters)"
            )
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        TransferSubmissionResult result = submissionService.submitTransfer(idempotencyKey, request);
        if (result.isReplay()) {
            return ResponseEntity.ok(result.transfer());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.transfer());
    }

    @PostMapping("/{transferId}/process")
    @Operation(summary = "Post transfer to double-entry ledger", description = "Executes atomic double-entry ledger posting and balance updates for an accepted transfer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer processed (inspect status in body: SUCCEEDED or FAILED)"),
            @ApiResponse(responseCode = "404", description = "Transfer not found")
    })
    public ResponseEntity<TransferResponse> processTransfer(
            @Parameter(description = "Transfer UUID") @PathVariable UUID transferId
    ) {
        postingService.postTransfer(transferId);
        return ResponseEntity.ok(queryService.getTransfer(transferId));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get transfer details", description = "Retrieves transfer metadata and status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer found"),
            @ApiResponse(responseCode = "404", description = "Transfer not found")
    })
    public ResponseEntity<TransferResponse> getTransfer(
            @Parameter(description = "Transfer UUID") @PathVariable UUID transferId
    ) {
        return ResponseEntity.ok(queryService.getTransfer(transferId));
    }

    @GetMapping
    @Operation(summary = "Search transfers", description = "Paginated search across transfers with multiple filtering criteria.")
    @ApiResponse(responseCode = "200", description = "Paginated transfer search results")
    public ResponseEntity<PagedResponse<TransferResponse>> searchTransfers(
            @Parameter(description = "Exact transfer ID") @RequestParam(required = false) UUID transferId,
            @Parameter(description = "Transfer lifecycle status") @RequestParam(required = false) TransferStatus status,
            @Parameter(description = "Source account UUID") @RequestParam(required = false) UUID sourceAccountId,
            @Parameter(description = "Destination account UUID") @RequestParam(required = false) UUID destinationAccountId,
            @Parameter(description = "Account UUID (either source or destination)") @RequestParam(required = false) UUID accountId,
            @Parameter(description = "3-character currency code") @RequestParam(required = false) String currency,
            @Parameter(description = "Filter transfers created on or after (ISO-8601 UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,
            @Parameter(description = "Filter transfers created on or before (ISO-8601 UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") int size
    ) {
        TransferSearchCriteria criteria = TransferSearchCriteria.of(
                transferId,
                status,
                sourceAccountId,
                destinationAccountId,
                accountId,
                currency,
                fromDate,
                toDate,
                page,
                size
        );
        return ResponseEntity.ok(queryService.searchTransfers(criteria));
    }

    @GetMapping("/{transferId}/history")
    @Operation(summary = "Get transfer status history", description = "Retrieves chronological status transitions for a transfer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status history entries"),
            @ApiResponse(responseCode = "404", description = "Transfer not found")
    })
    public ResponseEntity<List<TransferStatusHistoryResponse>> getTransferHistory(
            @Parameter(description = "Transfer UUID") @PathVariable UUID transferId
    ) {
        return ResponseEntity.ok(queryService.getTransferHistory(transferId));
    }

    @GetMapping("/{transferId}/attempts")
    @Operation(summary = "Get transfer processing attempts", description = "Retrieves execution audit details for processing attempts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Processing attempt details"),
            @ApiResponse(responseCode = "404", description = "Transfer not found")
    })
    public ResponseEntity<List<ProcessingAttemptResponse>> getProcessingAttempts(
            @Parameter(description = "Transfer UUID") @PathVariable UUID transferId
    ) {
        return ResponseEntity.ok(queryService.getProcessingAttempts(transferId));
    }
}
