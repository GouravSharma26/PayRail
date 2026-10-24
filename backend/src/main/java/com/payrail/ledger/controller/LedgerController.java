package com.payrail.ledger.controller;

import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.common.model.PagedResponse;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.dto.LedgerEntryResponse;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.account.repository.AccountRepository;
import com.payrail.transfer.repository.TransferRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Ledger", description = "Read-only inspection of immutable double-entry ledger rows")
public class LedgerController {

    private final LedgerRepository ledgerRepository;
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;

    public LedgerController(
            LedgerRepository ledgerRepository,
            TransferRepository transferRepository,
            AccountRepository accountRepository
    ) {
        this.ledgerRepository = ledgerRepository;
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/api/v1/transfers/{transferId}/ledger")
    @Operation(summary = "Get balanced ledger entries for a transfer", description = "Returns the double-entry legs (debit and credit) posted for a transfer.")
    public ResponseEntity<List<LedgerEntryResponse>> getTransferLedgerEntries(
            @PathVariable @Parameter(description = "Transfer UUID") UUID transferId
    ) {
        if (transferRepository.findById(transferId).isEmpty()) {
            throw new ResourceNotFoundException("Transfer with identifier '" + transferId + "' was not found.");
        }

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);
        List<LedgerEntryResponse> responses = entries.stream()
                .map(LedgerEntryResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/api/v1/accounts/{accountId}/ledger")
    @Operation(summary = "Get paginated ledger history for an account", description = "Returns immutable debit and credit ledger rows affecting an account, newest first.")
    public ResponseEntity<PagedResponse<LedgerEntryResponse>> getAccountLedgerEntries(
            @PathVariable @Parameter(description = "Account UUID") UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResourceNotFoundException("Account with identifier '" + accountId + "' was not found.");
        }

        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));
        int offset = boundedPage * boundedSize;

        List<LedgerEntryResponse> items = ledgerRepository.findByAccountId(accountId, offset, boundedSize).stream()
                .map(LedgerEntryResponse::from)
                .toList();

        long total = ledgerRepository.countByAccountId(accountId);

        return ResponseEntity.ok(PagedResponse.of(items, boundedPage, boundedSize, total));
    }
}
