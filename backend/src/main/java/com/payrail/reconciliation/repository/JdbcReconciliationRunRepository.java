package com.payrail.reconciliation.repository;

import com.payrail.reconciliation.domain.ReconciliationRunRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReconciliationRunRepository implements ReconciliationRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<ReconciliationRunRecord> ROW_MAPPER = (rs, rowNum) -> new ReconciliationRunRecord(
            rs.getObject("id", UUID.class),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null,
            rs.getString("status"),
            rs.getLong("scanned_transfers_count"),
            rs.getLong("scanned_accounts_count"),
            rs.getInt("issue_count"),
            rs.getString("trigger_type"),
            rs.getString("summary")
    );

    public JdbcReconciliationRunRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(ReconciliationRunRecord run) {
        String sql = """
                INSERT INTO reconciliation_runs (
                    id, started_at, finished_at, status, scanned_transfers_count,
                    scanned_accounts_count, issue_count, trigger_type, summary
                ) VALUES (
                    :id, :startedAt, :finishedAt, :status, :scannedTransfersCount,
                    :scannedAccountsCount, :issueCount, :triggerType, :summary
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", run.id())
                .addValue("startedAt", Timestamp.from(run.startedAt()))
                .addValue("finishedAt", run.finishedAt() != null ? Timestamp.from(run.finishedAt()) : null)
                .addValue("status", run.status())
                .addValue("scannedTransfersCount", run.scannedTransfersCount())
                .addValue("scannedAccountsCount", run.scannedAccountsCount())
                .addValue("issueCount", run.issueCount())
                .addValue("triggerType", run.triggerType())
                .addValue("summary", run.summary());

        jdbcTemplate.update(sql, params);
    }

    @Override
    public Optional<ReconciliationRunRecord> findById(UUID id) {
        String sql = "SELECT * FROM reconciliation_runs WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<ReconciliationRunRecord> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Override
    public List<ReconciliationRunRecord> findRecent(int limit) {
        String sql = "SELECT * FROM reconciliation_runs ORDER BY started_at DESC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }
}
