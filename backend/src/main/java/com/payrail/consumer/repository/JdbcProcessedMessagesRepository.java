package com.payrail.consumer.repository;

import com.payrail.consumer.domain.ProcessedMessageRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProcessedMessagesRepository implements ProcessedMessagesRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<ProcessedMessageRecord> ROW_MAPPER = (rs, rowNum) -> new ProcessedMessageRecord(
            rs.getString("consumer_name"),
            rs.getObject("event_id", UUID.class),
            rs.getTimestamp("processed_at").toInstant(),
            rs.getString("outcome")
    );

    public JdbcProcessedMessagesRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(ProcessedMessageRecord record) {
        String sql = """
                INSERT INTO processed_messages (consumer_name, event_id, processed_at, outcome)
                VALUES (:consumerName, :eventId, :processedAt, :outcome)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("consumerName", record.consumerName())
                .addValue("eventId", record.eventId())
                .addValue("processedAt", Timestamp.from(record.processedAt()))
                .addValue("outcome", record.outcome());

        jdbcTemplate.update(sql, params);
    }

    @Override
    public boolean exists(String consumerName, UUID eventId) {
        String sql = "SELECT COUNT(*) FROM processed_messages WHERE consumer_name = :consumerName AND event_id = :eventId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("consumerName", consumerName)
                .addValue("eventId", eventId);

        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }

    @Override
    public Optional<ProcessedMessageRecord> find(String consumerName, UUID eventId) {
        String sql = "SELECT * FROM processed_messages WHERE consumer_name = :consumerName AND event_id = :eventId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("consumerName", consumerName)
                .addValue("eventId", eventId);

        List<ProcessedMessageRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }
}
