package com.payrail.ledger.service;

import java.time.Instant;
import java.util.List;

/**
 * Audit and reconciliation findings report.
 */
public record ReconciliationReport(
        Instant generatedAt,
        boolean isBalanced,
        long totalAccountsChecked,
        long inconsistentAccountBalancesCount,
        long totalTransfersChecked,
        long unbalancedTransfersCount,
        long totalLedgerEntriesCount,
        List<String> discrepancyDetails
) {
}
