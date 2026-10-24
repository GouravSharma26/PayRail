package com.payrail.transfer.repository;

import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.domain.TransferStatusHistoryRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcTransferStatusHistoryRepository implements TransferStatusHistoryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<TransferStatusHistoryRecord> ROW_MAPPER = (rs, rowNum) -> new TransferStatusHistoryRecord(
            rs.getLong("id"),
            rs.getObject("transfer_id", UUID.class),
            rs.getString("from_status") != null ? TransferStatus.valueOf(rs.getString("from_status")) : null,
            TransferStatus.valueOf(rs.getString("to_status")),
            rs.getString("reason_code"),
            rs.getObject("attempt_number", Integer.class),
            rs.getObject("correlation_id", UUID.class),
            rs.getTimestamp("occurred_at").toInstant()
    );

    public JdbcTransferStatusHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(TransferStatusHistoryRecord record) {
        String sql = """
                INSERT INTO transfer_status_history (
                    transfer_id, from_status, to_status, reason_code, attempt_number, correlation_id, occurred_at
                ) VALUES (
                    :transferId, :fromStatus, :toStatus, :reasonCode, :attemptNumber, :correlationId, :occurredAt
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("transferId", record.transferId())
                .addValue("fromStatus", record.fromStatus() != null ? record.fromStatus().name() : null)
                .addValue("toStatus", record.toStatus().name())
                .addValue("reasonCode", record.reasonCode())
                .addValue("attemptNumber", record.attemptNumber())
                .addValue("correlationId", record.correlationId())
                .addValue("occurredAt", Timestamp.from(record.occurredAt()));

        jdbcTemplate.update(sql, params);
    }

    @Override
    public List<TransferStatusHistoryRecord> findByTransferId(UUID transferId) {
        String sql = "SELECT * FROM transfer_status_history WHERE transfer_id = :transferId ORDER BY occurred_at ASC, id ASC";
        MapSqlParameterSource params = new MapSqlParameterSource("transferId", transferId);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }
}
