package com.payrail.account.controller;

import com.payrail.account.dto.AccountResponse;
import com.payrail.account.dto.CreateAccountRequest;
import com.payrail.account.service.AccountService;
import com.payrail.common.model.PagedResponse;
import com.payrail.transfer.dto.TransferResponse;
import com.payrail.transfer.dto.TransferSearchCriteria;
import com.payrail.transfer.service.TransferQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Operations for managing synthetic accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransferQueryService transferQueryService;

    public AccountController(AccountService accountService, TransferQueryService transferQueryService) {
        this.accountService = accountService;
        this.transferQueryService = transferQueryService;
    }

    @PostMapping
    @Operation(summary = "Create synthetic account", description = "Creates a fictional account with an immutable opening balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid amount scale")
    })
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details", description = "Retrieves account metadata and current balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "404", description = "Account not found")
    })
    public ResponseEntity<AccountResponse> getAccount(
            @Parameter(description = "Account UUID") @PathVariable UUID accountId
    ) {
        return ResponseEntity.ok(accountService.getAccount(accountId));
    }

    @GetMapping
    @Operation(summary = "List accounts", description = "Lists accounts with bounded pagination and optional filters.")
    @ApiResponse(responseCode = "200", description = "Paginated list of accounts")
    public ResponseEntity<PagedResponse<AccountResponse>> listAccounts(
            @Parameter(description = "Filter by status (ACTIVE, INACTIVE)") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by 3-letter currency code") @RequestParam(required = false) String currency,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(accountService.listAccounts(status, currency, page, size));
    }

    @GetMapping("/{accountId}/transfers")
    @Operation(summary = "Get transfers for account", description = "Returns paginated transfers where this account is source or destination.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated transfer list"),
            @ApiResponse(responseCode = "404", description = "Account not found")
    })
    public ResponseEntity<PagedResponse<TransferResponse>> getAccountTransfers(
            @Parameter(description = "Account UUID") @PathVariable UUID accountId,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") int size
    ) {
        // Confirm account exists
        accountService.getAccount(accountId);

        TransferSearchCriteria criteria = TransferSearchCriteria.of(
                null,
                null,
                null,
                null,
                accountId,
                null,
                null,
                null,
                page,
                size
        );
        return ResponseEntity.ok(transferQueryService.searchTransfers(criteria));
    }
}
