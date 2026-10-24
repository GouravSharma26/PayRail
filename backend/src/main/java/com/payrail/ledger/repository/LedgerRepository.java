package com.payrail.ledger.repository;

import com.payrail.ledger.domain.LedgerEntryRecord;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Append-only repository for immutable double-entry ledger rows.
 * Mutating or deleting committed ledger entries is strictly prohibited.
 */
public interface LedgerRepository {

    void insert(LedgerEntryRecord entry);

    void insertPair(LedgerEntryRecord debit, LedgerEntryRecord credit);

    List<LedgerEntryRecord> findByTransferId(UUID transferId);

    List<LedgerEntryRecord> findByAccountId(UUID accountId, int offset, int limit);

    long countByAccountId(UUID accountId);

    BigDecimal calculateNetMovementForAccount(UUID accountId);

    long countTotalEntries();

    long countUnbalancedTransfers();
}
