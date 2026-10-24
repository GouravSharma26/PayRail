package com.payrail.outbox.repository;

import com.payrail.outbox.domain.OutboxEventRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<OutboxEventRecord> ROW_MAPPER = (rs, rowNum) -> new OutboxEventRecord(
            rs.getObject("event_id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getLong("aggregate_sequence"),
            rs.getString("event_type"),
            rs.getInt("schema_version"),
            rs.getString("payload"),
            rs.getObject("correlation_id", UUID.class),
            rs.getTimestamp("available_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant() : null,
            rs.getInt("publish_attempts"),
            rs.getObject("lease_token", UUID.class),
            rs.getTimestamp("lease_until") != null ? rs.getTimestamp("lease_until").toInstant() : null,
            rs.getString("last_error_code"),
            rs.getString("last_error_message")
    );

    public JdbcOutboxRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(OutboxEventRecord event) {
        String sql = """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, aggregate_sequence,
                    event_type, schema_version, payload, correlation_id,
                    available_at, created_at, published_at, publish_attempts,
                    lease_token, lease_until, last_error_code, last_error_message
                ) VALUES (
                    :eventId, :aggregateType, :aggregateId, :aggregateSequence,
                    :eventType, :schemaVersion, CAST(:payload AS jsonb), :correlationId,
                    :availableAt, :createdAt, :publishedAt, :publishAttempts,
                    :leaseToken, :leaseUntil, :lastErrorCode, :lastErrorMessage
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("aggregateType", event.aggregateType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("aggregateSequence", event.aggregateSequence())
                .addValue("eventType", event.eventType())
                .addValue("schemaVersion", event.schemaVersion())
                .addValue("payload", event.payloadJson())
                .addValue("correlationId", event.correlationId())
                .addValue("availableAt", Timestamp.from(event.availableAt()))
                .addValue("createdAt", Timestamp.from(event.createdAt()))
                .addValue("publishedAt", event.publishedAt() != null ? Timestamp.from(event.publishedAt()) : null)
                .addValue("publishAttempts", event.publishAttempts())
                .addValue("leaseToken", event.leaseToken())
                .addValue("leaseUntil", event.leaseUntil() != null ? Timestamp.from(event.leaseUntil()) : null)
                .addValue("lastErrorCode", event.lastErrorCode())
                .addValue("lastErrorMessage", event.lastErrorMessage());

        jdbcTemplate.update(sql, params);
    }

    @Override
    public Optional<OutboxEventRecord> findById(UUID eventId) {
        String sql = "SELECT * FROM outbox_events WHERE event_id = :eventId";
        MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);
        List<OutboxEventRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public List<OutboxEventRecord> claimDueEvents(int batchSize, Duration leaseDuration, Instant now, UUID leaseToken) {
        String selectSql = """
                SELECT *
                FROM outbox_events
                WHERE published_at IS NULL
                  AND available_at <= :now
                  AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY available_at ASC, created_at ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """;

        MapSqlParameterSource selectParams = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("batchSize", batchSize);

        List<OutboxEventRecord> due = jdbcTemplate.query(selectSql, selectParams, ROW_MAPPER);
        if (due.isEmpty()) {
            return Collections.emptyList();
        }

        Instant leaseUntil = now.plus(leaseDuration);
        List<UUID> eventIds = due.stream().map(OutboxEventRecord::eventId).toList();

        String updateSql = """
                UPDATE outbox_events
                SET lease_token = :leaseToken,
                    lease_until = :leaseUntil,
                    publish_attempts = publish_attempts + 1
                WHERE event_id IN (:eventIds)
                """;

        MapSqlParameterSource updateParams = new MapSqlParameterSource()
                .addValue("leaseToken", leaseToken)
                .addValue("leaseUntil", Timestamp.from(leaseUntil))
                .addValue("eventIds", eventIds);

        jdbcTemplate.update(updateSql, updateParams);

        // Return updated in-memory records with the active lease
        List<OutboxEventRecord> claimed = new ArrayList<>(due.size());
        for (OutboxEventRecord event : due) {
            claimed.add(new OutboxEventRecord(
                    event.eventId(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.aggregateSequence(),
                    event.eventType(),
                    event.schemaVersion(),
                    event.payloadJson(),
                    event.correlationId(),
                    event.availableAt(),
                    event.createdAt(),
                    event.publishedAt(),
                    event.publishAttempts() + 1,
                    leaseToken,
                    leaseUntil,
                    event.lastErrorCode(),
                    event.lastErrorMessage()
            ));
        }
        return claimed;
    }

    @Override
    public boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
        String sql = """
                UPDATE outbox_events
                SET published_at = :publishedAt,
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL
                WHERE event_id = :eventId
                  AND lease_token = :leaseToken
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("publishedAt", Timestamp.from(publishedAt))
                .addValue("eventId", eventId)
                .addValue("leaseToken", leaseToken);

        return jdbcTemplate.update(sql, params) > 0;
    }

    @Override
    public boolean recordPublishFailure(UUID eventId, UUID leaseToken, String errorCode, String errorMessage, Instant nextAvailableAt) {
        String sql = """
                UPDATE outbox_events
                SET lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = :errorCode,
                    last_error_message = :errorMessage,
                    available_at = :nextAvailableAt
                WHERE event_id = :eventId
                  AND lease_token = :leaseToken
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("errorCode", errorCode)
                .addValue("errorMessage", errorMessage != null && errorMessage.length() > 300 ? errorMessage.substring(0, 300) : errorMessage)
                .addValue("nextAvailableAt", Timestamp.from(nextAvailableAt))
                .addValue("eventId", eventId)
                .addValue("leaseToken", leaseToken);

        return jdbcTemplate.update(sql, params) > 0;
    }

    @Override
    public int releaseStaleLeases(Instant now) {
        String sql = """
                UPDATE outbox_events
                SET lease_token = NULL,
                    lease_until = NULL,
                    available_at = :now
                WHERE published_at IS NULL
                  AND lease_until IS NOT NULL
                  AND lease_until < :now
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(now));
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public List<OutboxEventRecord> findUnpublished(Instant threshold) {
        String sql = """
                SELECT *
                FROM outbox_events
                WHERE published_at IS NULL
                  AND created_at <= :threshold
                ORDER BY created_at ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("threshold", Timestamp.from(threshold));
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public long countUnpublished(Instant threshold) {
        String sql = """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE published_at IS NULL
                  AND created_at <= :threshold
                """;

        MapSqlParameterSource params = new MapSqlParameterSource("threshold", Timestamp.from(threshold));
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null ? count : 0L;
    }
}
