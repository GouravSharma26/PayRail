package com.payrail.transfer.dto;

import com.payrail.transfer.domain.TransferStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferSearchCriteria(
        UUID transferId,
        TransferStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        UUID accountId, // either source or destination
        String currency,
        Instant fromDate,
        Instant toDate,
        int page,
        int size
) {
    public static TransferSearchCriteria of(
            UUID transferId,
            TransferStatus status,
            UUID sourceAccountId,
            UUID destinationAccountId,
            UUID accountId,
            String currency,
            Instant fromDate,
            Instant toDate,
            int page,
            int size
    ) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));
        String normalizedCurrency = currency != null && !currency.isBlank() ? currency.trim().toUpperCase() : null;
        return new TransferSearchCriteria(
                transferId,
                status,
                sourceAccountId,
                destinationAccountId,
                accountId,
                normalizedCurrency,
                fromDate,
                toDate,
                boundedPage,
                boundedSize
        );
    }
}
