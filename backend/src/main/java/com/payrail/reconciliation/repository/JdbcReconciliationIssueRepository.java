package com.payrail.reconciliation.repository;

import com.payrail.reconciliation.domain.ReconciliationIssueRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReconciliationIssueRepository implements ReconciliationIssueRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final RowMapper<ReconciliationIssueRecord> ROW_MAPPER = (rs, rowNum) -> new ReconciliationIssueRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getString("issue_type"),
            rs.getString("severity"),
            rs.getObject("account_id", UUID.class),
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("outbox_event_id", UUID.class),
            rs.getTimestamp("detected_at").toInstant(),
            rs.getString("details"),
            rs.getString("status"),
            rs.getString("resolution_note")
    );

    public JdbcReconciliationIssueRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(ReconciliationIssueRecord issue) {
        String sql = """
                INSERT INTO reconciliation_issues (
                    id, run_id, issue_type, severity, account_id, transfer_id,
                    outbox_event_id, detected_at, details, status, resolution_note
                ) VALUES (
                    :id, :runId, :issueType, :severity, :accountId, :transferId,
                    :outboxEventId, :detectedAt, CAST(:details AS jsonb), :status, :resolutionNote
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", issue.id())
                .addValue("runId", issue.runId())
                .addValue("issueType", issue.issueType())
                .addValue("severity", issue.severity())
                .addValue("accountId", issue.accountId())
                .addValue("transferId", issue.transferId())
                .addValue("outboxEventId", issue.outboxEventId())
                .addValue("detectedAt", Timestamp.from(issue.detectedAt()))
                .addValue("details", issue.detailsJson() != null ? issue.detailsJson() : "{}")
                .addValue("status", issue.status())
                .addValue("resolutionNote", issue.resolutionNote());

        jdbcTemplate.update(sql, params);
    }

    @Override
    public void insertAll(List<ReconciliationIssueRecord> issues) {
        for (ReconciliationIssueRecord issue : issues) {
            insert(issue);
        }
    }

    @Override
    public List<ReconciliationIssueRecord> findByRunId(UUID runId) {
        String sql = "SELECT * FROM reconciliation_issues WHERE run_id = :runId ORDER BY detected_at ASC";
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public List<ReconciliationIssueRecord> findByStatus(String status) {
        String sql = "SELECT * FROM reconciliation_issues WHERE status = :status ORDER BY detected_at DESC";
        MapSqlParameterSource params = new MapSqlParameterSource("status", status);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }
}
