package com.payrail.outbox.repository;

import com.payrail.outbox.domain.OutboxEventRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
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
}
