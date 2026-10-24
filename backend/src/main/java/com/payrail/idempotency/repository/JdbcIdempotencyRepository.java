package com.payrail.idempotency.repository;

import com.payrail.idempotency.domain.IdempotencyRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("transfer_id", UUID.class),
            rs.getTimestamp("created_at").toInstant()
    );

    public JdbcIdempotencyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(IdempotencyRecord record) {
        String sql = """
                INSERT INTO idempotency_records (idempotency_key, request_hash, transfer_id, created_at)
                VALUES (:idempotencyKey, :requestHash, :transferId, :createdAt)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("idempotencyKey", record.idempotencyKey())
                .addValue("requestHash", record.requestHash())
                .addValue("transferId", record.transferId())
                .addValue("createdAt", Timestamp.from(record.createdAt()));

        jdbcTemplate.update(sql, params);
    }

    @Override
    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        String sql = "SELECT * FROM idempotency_records WHERE idempotency_key = :idempotencyKey";
        MapSqlParameterSource params = new MapSqlParameterSource("idempotencyKey", idempotencyKey);
        List<IdempotencyRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }
}
