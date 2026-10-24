package com.payrail.transfer.repository;

import com.payrail.transfer.domain.ProcessingAttemptRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProcessingAttemptRepository implements ProcessingAttemptRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<ProcessingAttemptRecord> ROW_MAPPER = (rs, rowNum) -> new ProcessingAttemptRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("transfer_id", UUID.class),
            rs.getInt("attempt_number"),
            rs.getObject("claim_token", UUID.class),
            rs.getString("outcome"),
            rs.getString("error_code"),
            rs.getString("safe_error_message"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null,
            rs.getObject("duration_ms", Long.class)
    );

    public JdbcProcessingAttemptRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(ProcessingAttemptRecord record) {
        String sql = """
                INSERT INTO processing_attempts (
                    id, transfer_id, attempt_number, claim_token, outcome, error_code,
                    safe_error_message, started_at, finished_at, duration_ms
                ) VALUES (
                    :id, :transferId, :attemptNumber, :claimToken, :outcome, :errorCode,
                    :safeErrorMessage, :startedAt, :finishedAt, :durationMs
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("transferId", record.transferId())
                .addValue("attemptNumber", record.attemptNumber())
                .addValue("claimToken", record.claimToken())
                .addValue("outcome", record.outcome())
                .addValue("errorCode", record.errorCode())
                .addValue("safeErrorMessage", record.safeErrorMessage())
                .addValue("startedAt", Timestamp.from(record.startedAt()))
                .addValue("finishedAt", record.finishedAt() != null ? Timestamp.from(record.finishedAt()) : null)
                .addValue("durationMs", record.durationMs());

        jdbcTemplate.update(sql, params);
    }

    @Override
    public List<ProcessingAttemptRecord> findByTransferId(UUID transferId) {
        String sql = "SELECT * FROM processing_attempts WHERE transfer_id = :transferId ORDER BY attempt_number ASC";
        MapSqlParameterSource params = new MapSqlParameterSource("transferId", transferId);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public Optional<ProcessingAttemptRecord> findByTransferIdAndAttempt(UUID transferId, int attemptNumber) {
        String sql = "SELECT * FROM processing_attempts WHERE transfer_id = :transferId AND attempt_number = :attemptNumber";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("transferId", transferId)
                .addValue("attemptNumber", attemptNumber);

        List<ProcessingAttemptRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    public void updateOutcome(
            UUID transferId,
            int attemptNumber,
            String outcome,
            String errorCode,
            String safeErrorMessage,
            Instant finishedAt,
            Long durationMs
    ) {
        String sql = """
                UPDATE processing_attempts
                SET outcome = :outcome,
                    error_code = :errorCode,
                    safe_error_message = :safeErrorMessage,
                    finished_at = :finishedAt,
                    duration_ms = :durationMs
                WHERE transfer_id = :transferId
                  AND attempt_number = :attemptNumber
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("outcome", outcome)
                .addValue("errorCode", errorCode)
                .addValue("safeErrorMessage", safeErrorMessage != null && safeErrorMessage.length() > 300 ? safeErrorMessage.substring(0, 300) : safeErrorMessage)
                .addValue("finishedAt", finishedAt != null ? Timestamp.from(finishedAt) : null)
                .addValue("durationMs", durationMs)
                .addValue("transferId", transferId)
                .addValue("attemptNumber", attemptNumber);

        jdbcTemplate.update(sql, params);
    }

    @Override
    public boolean finalizeStaleAttempt(
            UUID transferId,
            int attemptNumber,
            UUID claimToken,
            String outcome,
            String errorCode,
            String safeErrorMessage,
            Instant finishedAt
    ) {
        String sql = """
                UPDATE processing_attempts
                SET outcome = :outcome,
                    error_code = :errorCode,
                    safe_error_message = :safeErrorMessage,
                    finished_at = :finishedAt,
                    duration_ms = GREATEST(0, CAST(EXTRACT(EPOCH FROM (:finishedAt - started_at)) * 1000 AS BIGINT))
                WHERE transfer_id = :transferId
                  AND attempt_number = :attemptNumber
                  AND claim_token = :claimToken
                  AND outcome = 'IN_PROGRESS'
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("outcome", outcome)
                .addValue("errorCode", errorCode)
                .addValue("safeErrorMessage", safeErrorMessage != null && safeErrorMessage.length() > 300 ? safeErrorMessage.substring(0, 300) : safeErrorMessage)
                .addValue("finishedAt", Timestamp.from(finishedAt))
                .addValue("transferId", transferId)
                .addValue("attemptNumber", attemptNumber)
                .addValue("claimToken", claimToken);

        return jdbcTemplate.update(sql, params) > 0;
    }
}
