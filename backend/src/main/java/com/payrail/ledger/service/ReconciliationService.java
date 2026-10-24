package com.payrail.ledger.service;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.repository.AccountRepository;
import com.payrail.common.exception.ResourceNotFoundException;
import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import com.payrail.ledger.repository.LedgerRepository;
import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Continuous audit and reconciliation engine.
 * Detects discrepancies between ledger entries, account balances, and transfer states.
 * Under no circumstances does reconciliation auto-repair or alter ledger history.
 */
@Service
@Transactional(readOnly = true)
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerRepository ledgerRepository;

    public ReconciliationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerRepository ledgerRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Verifies that an individual account's stored current balance matches its
     * opening balance plus net ledger movements.
     */
    public boolean verifyAccountBalanceConsistency(UUID accountId) {
        AccountRecord account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account with identifier '" + accountId + "' was not found."));

        BigDecimal netMovement = ledgerRepository.calculateNetMovementForAccount(accountId);
        BigDecimal expectedBalance = account.openingBalance().add(netMovement);

        boolean matches = expectedBalance.compareTo(account.currentBalance()) == 0;
        if (!matches) {
            log.error("AUDIT MISMATCH on account id={}: opening={}, netMovement={}, expected={}, actual={}",
                    accountId, account.openingBalance(), netMovement, expectedBalance, account.currentBalance());
        }
        return matches;
    }

    /**
     * Verifies that a transfer has balanced double-entry ledger legs.
     */
    public boolean verifyTransferLedgerBalance(UUID transferId) {
        TransferRecord transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer with identifier '" + transferId + "' was not found."));

        List<LedgerEntryRecord> entries = ledgerRepository.findByTransferId(transferId);

        if (transfer.status() != TransferStatus.SUCCEEDED) {
            return entries.isEmpty();
        }

        if (entries.size() != 2) {
            return false;
        }

        LedgerEntryRecord debit = entries.stream().filter(e -> e.leg() == LedgerLeg.DEBIT).findFirst().orElse(null);
        LedgerEntryRecord credit = entries.stream().filter(e -> e.leg() == LedgerLeg.CREDIT).findFirst().orElse(null);

        if (debit == null || credit == null) {
            return false;
        }

        return debit.amount().compareTo(transfer.amount()) == 0
                && credit.amount().compareTo(transfer.amount()) == 0
                && debit.currency().equals(transfer.currency())
                && credit.currency().equals(transfer.currency())
                && debit.accountId().equals(transfer.sourceAccountId())
                && credit.accountId().equals(transfer.destinationAccountId());
    }

    /**
     * Runs full database-wide audit across all accounts, transfers, and ledger entries.
     */
    public ReconciliationReport runFullAudit() {
        List<String> discrepancies = new ArrayList<>();

        // 1. Check account balance projection integrity
        String accountMismatchSql = """
                SELECT a.id, a.current_balance, a.opening_balance,
                       COALESCE(SUM(CASE WHEN l.leg = 'CREDIT' THEN l.amount ELSE -l.amount END), 0.00) AS net_movement
                FROM accounts a
                LEFT JOIN ledger_entries l ON a.id = l.account_id
                GROUP BY a.id, a.current_balance, a.opening_balance
                HAVING a.current_balance <> (a.opening_balance + COALESCE(SUM(CASE WHEN l.leg = 'CREDIT' THEN l.amount ELSE -l.amount END), 0.00))
                """;

        List<String> mismatchedAccounts = jdbcTemplate.query(accountMismatchSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Account %s balance mismatch: opening=%s, net_movement=%s, current=%s",
                        rs.getString("id"),
                        rs.getBigDecimal("opening_balance"),
                        rs.getBigDecimal("net_movement"),
                        rs.getBigDecimal("current_balance")
                )
        );
        discrepancies.addAll(mismatchedAccounts);

        // 2. Check unbalanced ledger entries per transfer
        String unbalancedTransfersSql = """
                SELECT t.id,
                       SUM(CASE WHEN l.leg = 'DEBIT' THEN l.amount ELSE 0 END) AS total_debit,
                       SUM(CASE WHEN l.leg = 'CREDIT' THEN l.amount ELSE 0 END) AS total_credit,
                       COUNT(l.id) AS leg_count
                FROM transfers t
                JOIN ledger_entries l ON t.id = l.transfer_id
                GROUP BY t.id
                HAVING COUNT(l.id) <> 2
                   OR SUM(CASE WHEN l.leg = 'DEBIT' THEN l.amount ELSE 0 END) <> SUM(CASE WHEN l.leg = 'CREDIT' THEN l.amount ELSE 0 END)
                """;

        List<String> unbalancedTransfers = jdbcTemplate.query(unbalancedTransfersSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Transfer %s has unbalanced legs: debits=%s, credits=%s, count=%d",
                        rs.getString("id"),
                        rs.getBigDecimal("total_debit"),
                        rs.getBigDecimal("total_credit"),
                        rs.getInt("leg_count")
                )
        );
        discrepancies.addAll(unbalancedTransfers);

        // 3. Check for SUCCEEDED transfers with missing ledger entries
        String missingLedgerSql = """
                SELECT t.id
                FROM transfers t
                LEFT JOIN ledger_entries l ON t.id = l.transfer_id
                WHERE t.status = 'SUCCEEDED'
                GROUP BY t.id
                HAVING COUNT(l.id) <> 2
                """;
        List<String> missingLedgerTransfers = jdbcTemplate.query(missingLedgerSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("SUCCEEDED Transfer %s does not have exactly 2 ledger entries", rs.getString("id"))
        );
        discrepancies.addAll(missingLedgerTransfers);

        // 4. Check for non-SUCCEEDED transfers with orphan ledger entries
        String nonSucceededWithLedgerSql = """
                SELECT DISTINCT t.id, t.status
                FROM transfers t
                JOIN ledger_entries l ON t.id = l.transfer_id
                WHERE t.status <> 'SUCCEEDED'
                """;
        List<String> illegalLedgerTransfers = jdbcTemplate.query(nonSucceededWithLedgerSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Transfer %s has status %s but contains ledger entries", rs.getString("id"), rs.getString("status"))
        );
        discrepancies.addAll(illegalLedgerTransfers);

        // 5. Check DEBIT account mapping (must equal transfer.source_account_id)
        String debitAccountMismatchSql = """
                SELECT l.transfer_id, l.account_id AS ledger_account, t.source_account_id AS expected_account
                FROM ledger_entries l
                JOIN transfers t ON l.transfer_id = t.id
                WHERE l.leg = 'DEBIT' AND l.account_id <> t.source_account_id
                """;
        List<String> debitMismatches = jdbcTemplate.query(debitAccountMismatchSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Transfer %s DEBIT account mismatch: ledger_account=%s, expected_source=%s",
                        rs.getString("transfer_id"), rs.getString("ledger_account"), rs.getString("expected_account"))
        );
        discrepancies.addAll(debitMismatches);

        // 6. Check CREDIT account mapping (must equal transfer.destination_account_id)
        String creditAccountMismatchSql = """
                SELECT l.transfer_id, l.account_id AS ledger_account, t.destination_account_id AS expected_account
                FROM ledger_entries l
                JOIN transfers t ON l.transfer_id = t.id
                WHERE l.leg = 'CREDIT' AND l.account_id <> t.destination_account_id
                """;
        List<String> creditMismatches = jdbcTemplate.query(creditAccountMismatchSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Transfer %s CREDIT account mismatch: ledger_account=%s, expected_destination=%s",
                        rs.getString("transfer_id"), rs.getString("ledger_account"), rs.getString("expected_account"))
        );
        discrepancies.addAll(creditMismatches);

        // 7. Check leg amount matching transfer amount
        String amountMismatchSql = """
                SELECT l.transfer_id, l.leg, l.amount AS ledger_amount, t.amount AS transfer_amount
                FROM ledger_entries l
                JOIN transfers t ON l.transfer_id = t.id
                WHERE l.amount <> t.amount
                """;
        List<String> amountMismatches = jdbcTemplate.query(amountMismatchSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Transfer %s %s amount mismatch: ledger=%s, transfer=%s",
                        rs.getString("transfer_id"), rs.getString("leg"), rs.getBigDecimal("ledger_amount"), rs.getBigDecimal("transfer_amount"))
        );
        discrepancies.addAll(amountMismatches);

        // 8. Check currency matching between ledger and transfer
        String currencyMismatchSql = """
                SELECT l.transfer_id, l.currency AS ledger_currency, t.currency AS transfer_currency
                FROM ledger_entries l
                JOIN transfers t ON l.transfer_id = t.id
                WHERE l.currency <> t.currency
                """;
        List<String> currencyMismatches = jdbcTemplate.query(currencyMismatchSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Transfer %s currency mismatch: ledger=%s, transfer=%s",
                        rs.getString("transfer_id"), rs.getString("ledger_currency"), rs.getString("transfer_currency"))
        );
        discrepancies.addAll(currencyMismatches);

        // 9. Check currency matching between ledger entry and account currency
        String accountCurrencyMismatchSql = """
                SELECT l.id AS entry_id, l.currency AS ledger_currency, a.currency AS account_currency
                FROM ledger_entries l
                JOIN accounts a ON l.account_id = a.id
                WHERE l.currency <> a.currency
                """;
        List<String> accountCurrencyMismatches = jdbcTemplate.query(accountCurrencyMismatchSql, new MapSqlParameterSource(), (rs, rowNum) ->
                String.format("Ledger entry %s currency mismatch with account: ledger=%s, account=%s",
                        rs.getString("entry_id"), rs.getString("ledger_currency"), rs.getString("account_currency"))
        );
        discrepancies.addAll(accountCurrencyMismatches);

        Long totalAccounts = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", new MapSqlParameterSource(), Long.class);
        Long totalTransfers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", new MapSqlParameterSource(), Long.class);
        Long totalLedgerEntries = ledgerRepository.countTotalEntries();

        boolean isBalanced = discrepancies.isEmpty();

        return new ReconciliationReport(
                Instant.now(),
                isBalanced,
                totalAccounts != null ? totalAccounts : 0L,
                mismatchedAccounts.size(),
                totalTransfers != null ? totalTransfers : 0L,
                unbalancedTransfers.size() + missingLedgerTransfers.size() + illegalLedgerTransfers.size(),
                totalLedgerEntries,
                discrepancies
        );
    }
}
