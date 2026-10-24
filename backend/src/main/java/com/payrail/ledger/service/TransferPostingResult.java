package com.payrail.ledger.service;

import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.transfer.domain.TransferStatus;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Result of an atomic transfer posting operation.
 */
public record TransferPostingResult(
        UUID transferId,
        TransferStatus status,
        boolean isAlreadyProcessed,
        String failureCode,
        String failureMessage,
        List<LedgerEntryRecord> ledgerEntries
) {
    public static TransferPostingResult succeeded(UUID transferId, List<LedgerEntryRecord> entries) {
        return new TransferPostingResult(transferId, TransferStatus.SUCCEEDED, false, null, null, entries);
    }

    public static TransferPostingResult failed(UUID transferId, String failureCode, String failureMessage) {
        return new TransferPostingResult(transferId, TransferStatus.FAILED, false, failureCode, failureMessage, Collections.emptyList());
    }

    public static TransferPostingResult alreadyProcessed(UUID transferId, TransferStatus status, List<LedgerEntryRecord> entries) {
        return new TransferPostingResult(transferId, status, true, null, null, entries);
    }
}
