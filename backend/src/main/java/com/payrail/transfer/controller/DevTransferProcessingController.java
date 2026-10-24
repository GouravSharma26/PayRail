package com.payrail.transfer.controller;

import com.payrail.ledger.service.TransferPostingService;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.service.TransferQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Development and test utility endpoint for manual synchronous transfer posting.
 * Strictly disabled in production/default runtime unless explicitly enabled by
 * 'payrail.processing.manual-endpoint-enabled=true'.
 */
@RestController
@RequestMapping("/api/v1/transfers")
@ConditionalOnProperty(prefix = "payrail.processing", name = "manual-endpoint-enabled", havingValue = "true")
@Tag(name = "Development Ops", description = "Development and test utility endpoints for manual transfer posting. Strictly disabled in standard runtime.")
public class DevTransferProcessingController {

    private final TransferPostingService postingService;
    private final TransferQueryService queryService;

    public DevTransferProcessingController(
            TransferPostingService postingService,
            TransferQueryService queryService
    ) {
        this.postingService = postingService;
        this.queryService = queryService;
    }

    @PostMapping("/{transferId}/process")
    @Operation(
            summary = "Post transfer to double-entry ledger (Dev/Test Only)",
            description = "Development/test utility to synchronously execute atomic ledger posting for an accepted transfer. Unavailable in production."
    )
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
}
