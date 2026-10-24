package com.payrail.account.repository;

import com.payrail.account.domain.AccountRecord;
import com.payrail.account.domain.AccountStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<AccountRecord> ROW_MAPPER = (rs, rowNum) -> new AccountRecord(
            rs.getObject("id", UUID.class),
            rs.getString("display_name"),
            rs.getString("currency"),
            rs.getBigDecimal("opening_balance"),
            rs.getBigDecimal("current_balance"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public JdbcAccountRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(AccountRecord account) {
        String sql = """
                INSERT INTO accounts (id, display_name, currency, opening_balance, current_balance, status, created_at, updated_at)
                VALUES (:id, :displayName, :currency, :openingBalance, :currentBalance, :status, :createdAt, :updatedAt)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", account.id())
                .addValue("displayName", account.displayName())
                .addValue("currency", account.currency())
                .addValue("openingBalance", account.openingBalance())
                .addValue("currentBalance", account.currentBalance())
                .addValue("status", account.status().name())
                .addValue("createdAt", Timestamp.from(account.createdAt()))
                .addValue("updatedAt", Timestamp.from(account.updatedAt()));

        jdbcTemplate.update(sql, params);
    }

    @Override
    public Optional<AccountRecord> findById(UUID id) {
        String sql = "SELECT * FROM accounts WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<AccountRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    public List<AccountRecord> findAll(String status, String currency, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM accounts WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (status != null && !status.isBlank()) {
            sql.append("AND status = :status ");
            params.addValue("status", status.trim().toUpperCase());
        }
        if (currency != null && !currency.isBlank()) {
            sql.append("AND currency = :currency ");
            params.addValue("currency", currency.trim().toUpperCase());
        }

        sql.append("ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit");
        params.addValue("offset", Math.max(0, offset));
        params.addValue("limit", Math.min(100, Math.max(1, limit)));

        return jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
    }

    @Override
    public long count(String status, String currency) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM accounts WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (status != null && !status.isBlank()) {
            sql.append("AND status = :status ");
            params.addValue("status", status.trim().toUpperCase());
        }
        if (currency != null && !currency.isBlank()) {
            sql.append("AND currency = :currency ");
            params.addValue("currency", currency.trim().toUpperCase());
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public boolean existsById(UUID id) {
        String sql = "SELECT EXISTS(SELECT 1 FROM accounts WHERE id = :id)";
        Boolean exists = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("id", id), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }
}
