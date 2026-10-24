package com.payrail.transfer.dto;

public record TransferSubmissionResult(
        TransferResponse transfer,
        boolean isReplay
) {
}
