package com.payrail.transfer.repository;

import com.payrail.transfer.domain.TransferRecord;
import com.payrail.transfer.domain.TransferStatus;
import com.payrail.transfer.dto.TransferSearchCriteria;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransferRepository implements TransferRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<TransferRecord> ROW_MAPPER = (rs, rowNum) -> new TransferRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("source_account_id", UUID.class),
            rs.getObject("destination_account_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getString("failure_code"),
            rs.getString("failure_message"),
            rs.getObject("correlation_id", UUID.class),
            rs.getInt("processing_attempt_count"),
            rs.getObject("processing_claim_token", UUID.class),
            rs.getTimestamp("processing_lease_until") != null ? rs.getTimestamp("processing_lease_until").toInstant() : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
    );

    public JdbcTransferRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(TransferRecord transfer) {
        String sql = """
                INSERT INTO transfers (
                    id, source_account_id, destination_account_id, amount, currency,
                    status, failure_code, failure_message, correlation_id,
                    processing_attempt_count, processing_claim_token, processing_lease_until,
                    created_at, updated_at, completed_at
                ) VALUES (
                    :id, :sourceAccountId, :destinationAccountId, :amount, :currency,
                    :status, :failureCode, :failureMessage, :correlationId,
                    :processingAttemptCount, :processingClaimToken, :processingLeaseUntil,
                    :createdAt, :updatedAt, :completedAt
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", transfer.id())
                .addValue("sourceAccountId", transfer.sourceAccountId())
                .addValue("destinationAccountId", transfer.destinationAccountId())
                .addValue("amount", transfer.amount())
                .addValue("currency", transfer.currency())
                .addValue("status", transfer.status().name())
                .addValue("failureCode", transfer.failureCode())
                .addValue("failureMessage", transfer.failureMessage())
                .addValue("correlationId", transfer.correlationId())
                .addValue("processingAttemptCount", transfer.processingAttemptCount())
                .addValue("processingClaimToken", transfer.processingClaimToken())
                .addValue("processingLeaseUntil", transfer.processingLeaseUntil() != null ? Timestamp.from(transfer.processingLeaseUntil()) : null)
                .addValue("createdAt", Timestamp.from(transfer.createdAt()))
                .addValue("updatedAt", Timestamp.from(transfer.updatedAt()))
                .addValue("completedAt", transfer.completedAt() != null ? Timestamp.from(transfer.completedAt()) : null);

        jdbcTemplate.update(sql, params);
    }

    @Override
    public Optional<TransferRecord> findById(UUID id) {
        String sql = "SELECT * FROM transfers WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<TransferRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    public List<TransferRecord> search(TransferSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transfers WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        buildWhereClause(criteria, sql, params);

        sql.append("ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit");
        params.addValue("offset", criteria.page() * criteria.size());
        params.addValue("limit", criteria.size());

        return jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
    }

    @Override
    public long count(TransferSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM transfers WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        buildWhereClause(criteria, sql, params);

        Long count = jdbcTemplate.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    private void buildWhereClause(TransferSearchCriteria criteria, StringBuilder sql, MapSqlParameterSource params) {
        if (criteria.transferId() != null) {
            sql.append("AND id = :transferId ");
            params.addValue("transferId", criteria.transferId());
        }
        if (criteria.status() != null) {
            sql.append("AND status = :status ");
            params.addValue("status", criteria.status().name());
        }
        if (criteria.sourceAccountId() != null) {
            sql.append("AND source_account_id = :sourceAccountId ");
            params.addValue("sourceAccountId", criteria.sourceAccountId());
        }
        if (criteria.destinationAccountId() != null) {
            sql.append("AND destination_account_id = :destinationAccountId ");
            params.addValue("destinationAccountId", criteria.destinationAccountId());
        }
        if (criteria.accountId() != null) {
            sql.append("AND (source_account_id = :accountId OR destination_account_id = :accountId) ");
            params.addValue("accountId", criteria.accountId());
        }
        if (criteria.currency() != null && !criteria.currency().isBlank()) {
            sql.append("AND currency = :currency ");
            params.addValue("currency", criteria.currency());
        }
        if (criteria.fromDate() != null) {
            sql.append("AND created_at >= :fromDate ");
            params.addValue("fromDate", Timestamp.from(criteria.fromDate()));
        }
        if (criteria.toDate() != null) {
            sql.append("AND created_at <= :toDate ");
            params.addValue("toDate", Timestamp.from(criteria.toDate()));
        }
    }

    @Override
    public Optional<TransferRecord> findByIdForUpdate(UUID id) {
        String sql = "SELECT * FROM transfers WHERE id = :id FOR UPDATE";
        List<TransferRecord> results = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    public int updateStatus(
            UUID id,
            TransferStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt,
            Instant updatedAt
    ) {
        String sql = """
                UPDATE transfers
                SET status = :status,
                    failure_code = :failureCode,
                    failure_message = :failureMessage,
                    completed_at = :completedAt,
                    updated_at = :updatedAt
                WHERE id = :id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("failureCode", failureCode)
                .addValue("failureMessage", failureMessage)
                .addValue("completedAt", completedAt != null ? Timestamp.from(completedAt) : null)
                .addValue("updatedAt", Timestamp.from(updatedAt));

        return jdbcTemplate.update(sql, params);
    }

    @Override
    public boolean claimForProcessing(UUID id, UUID claimToken, Instant leaseUntil, Instant now) {
        String sql = """
                UPDATE transfers
                SET status = 'PROCESSING',
                    processing_claim_token = :claimToken,
                    processing_lease_until = :leaseUntil,
                    processing_attempt_count = processing_attempt_count + 1,
                    updated_at = :now
                WHERE id = :id
                  AND (status = 'PENDING' OR (status = 'PROCESSING' AND processing_lease_until < :now))
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("claimToken", claimToken)
                .addValue("leaseUntil", Timestamp.from(leaseUntil))
                .addValue("now", Timestamp.from(now));

        return jdbcTemplate.update(sql, params) > 0;
    }

    @Override
    public int completeProcessing(
            UUID id,
            TransferStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt,
            Instant updatedAt
    ) {
        String sql = """
                UPDATE transfers
                SET status = :status,
                    failure_code = :failureCode,
                    failure_message = :failureMessage,
                    completed_at = :completedAt,
                    processing_claim_token = NULL,
                    processing_lease_until = NULL,
                    updated_at = :updatedAt
                WHERE id = :id
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("failureCode", failureCode)
                .addValue("failureMessage", failureMessage)
                .addValue("completedAt", completedAt != null ? Timestamp.from(completedAt) : null)
                .addValue("updatedAt", Timestamp.from(updatedAt));

        return jdbcTemplate.update(sql, params);
    }

    @Override
    public int resetToPending(UUID id, Instant updatedAt) {
        String sql = """
                UPDATE transfers
                SET status = 'PENDING',
                    processing_claim_token = NULL,
                    processing_lease_until = NULL,
                    updated_at = :updatedAt
                WHERE id = :id
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("updatedAt", Timestamp.from(updatedAt));

        return jdbcTemplate.update(sql, params);
    }

    @Override
    public List<TransferRecord> findStaleProcessingLeases(Instant threshold) {
        String sql = """
                SELECT *
                FROM transfers
                WHERE status = 'PROCESSING'
                  AND processing_lease_until < :threshold
                ORDER BY created_at ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("threshold", Timestamp.from(threshold));
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public List<TransferRecord> findPendingWithoutOutbox(Instant threshold) {
        String sql = """
                SELECT t.*
                FROM transfers t
                WHERE t.status = 'PENDING'
                  AND t.created_at <= :threshold
                  AND NOT EXISTS (
                      SELECT 1
                      FROM outbox_events o
                      WHERE o.aggregate_type = 'transfer'
                        AND o.aggregate_id = t.id
                        AND o.published_at IS NULL
                        AND (o.last_error_code IS NULL OR o.last_error_code <> 'PUBLISH_RETRY_EXHAUSTED')
                  )
                ORDER BY t.created_at ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("threshold", Timestamp.from(threshold));
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }
}
