package com.payrail.ledger.repository;

import com.payrail.ledger.domain.LedgerEntryRecord;
import com.payrail.ledger.domain.LedgerLeg;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<LedgerEntryRecord> ROW_MAPPER = (rs, rowNum) -> new LedgerEntryRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            LedgerLeg.valueOf(rs.getString("leg")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getTimestamp("created_at").toInstant()
    );

    public JdbcLedgerRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(LedgerEntryRecord entry) {
        String sql = """
                INSERT INTO ledger_entries (id, transfer_id, account_id, leg, amount, currency, created_at)
                VALUES (:id, :transferId, :accountId, :leg, :amount, :currency, :createdAt)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", entry.id())
                .addValue("transferId", entry.transferId())
                .addValue("accountId", entry.accountId())
                .addValue("leg", entry.leg().name())
                .addValue("amount", entry.amount())
                .addValue("currency", entry.currency())
                .addValue("createdAt", Timestamp.from(entry.createdAt()));

        jdbcTemplate.update(sql, params);
    }

    @Override
    public void insertPair(LedgerEntryRecord debit, LedgerEntryRecord credit) {
        // Enforce balanced posting contract before SQL execution
        if (debit.leg() != LedgerLeg.DEBIT || credit.leg() != LedgerLeg.CREDIT) {
            throw new IllegalArgumentException("Ledger pair must contain exactly one DEBIT and one CREDIT leg");
        }
        if (debit.amount().compareTo(credit.amount()) != 0) {
            throw new IllegalArgumentException(String.format(
                    "Ledger pair amounts must match exactly: debit=%s, credit=%s",
                    debit.amount(), credit.amount()
            ));
        }
        if (!debit.currency().equals(credit.currency())) {
            throw new IllegalArgumentException(String.format(
                    "Ledger pair currencies must match: debit=%s, credit=%s",
                    debit.currency(), credit.currency()
            ));
        }
        if (!debit.transferId().equals(credit.transferId())) {
            throw new IllegalArgumentException("Ledger pair must reference the same transfer ID");
        }

        insert(debit);
        insert(credit);
    }

    @Override
    public List<LedgerEntryRecord> findByTransferId(UUID transferId) {
        String sql = "SELECT * FROM ledger_entries WHERE transfer_id = :transferId ORDER BY leg ASC";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("transferId", transferId), ROW_MAPPER);
    }

    @Override
    public List<LedgerEntryRecord> findByAccountId(UUID accountId, int offset, int limit) {
        String sql = """
                SELECT * FROM ledger_entries
                WHERE account_id = :accountId
                ORDER BY created_at DESC, id DESC
                OFFSET :offset LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("offset", Math.max(0, offset))
                .addValue("limit", Math.min(100, Math.max(1, limit)));

        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public long countByAccountId(UUID accountId) {
        String sql = "SELECT COUNT(*) FROM ledger_entries WHERE account_id = :accountId";
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("accountId", accountId), Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public BigDecimal calculateNetMovementForAccount(UUID accountId) {
        String sql = """
                SELECT COALESCE(
                    SUM(CASE WHEN leg = 'CREDIT' THEN amount ELSE -amount END),
                    0.00
                ) FROM ledger_entries WHERE account_id = :accountId
                """;
        BigDecimal net = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("accountId", accountId), BigDecimal.class);
        return net != null ? net : BigDecimal.ZERO.setScale(2);
    }

    @Override
    public long countTotalEntries() {
        String sql = "SELECT COUNT(*) FROM ledger_entries";
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long countUnbalancedTransfers() {
        String sql = """
                SELECT COUNT(*) FROM (
                    SELECT transfer_id
                    FROM ledger_entries
                    GROUP BY transfer_id
                    HAVING COUNT(*) <> 2
                       OR SUM(CASE WHEN leg = 'DEBIT' THEN amount ELSE 0 END) <> SUM(CASE WHEN leg = 'CREDIT' THEN amount ELSE 0 END)
                ) unbalanced
                """;
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0L;
    }
}
