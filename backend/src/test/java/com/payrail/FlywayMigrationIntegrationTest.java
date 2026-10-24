package com.payrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should successfully apply Flyway migration V1 to fresh PostgreSQL container")
    void shouldApplyMigrationsSuccessfully() {
        // 1. Verify flyway_schema_history records success
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM \"flyway_schema_history\" WHERE \"success\" = true",
                Integer.class
        );
        assertThat(successfulMigrations).isNotNull().isGreaterThanOrEqualTo(3);

        // 2. Verify all authoritative tables exist in public schema
        List<String> tableNames = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        assertThat(tableNames).contains(
                "accounts",
                "transfers",
                "idempotency_records",
                "ledger_entries",
                "transfer_status_history",
                "processing_attempts",
                "outbox_events",
                "processed_messages",
                "reconciliation_runs",
                "reconciliation_issues"
        );

        // 3. Verify PostgreSQL triggers exist
        List<String> triggers = jdbcTemplate.queryForList(
                "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema = 'public'",
                String.class
        );
        assertThat(triggers).contains(
                "trg_prevent_ledger_update_or_delete",
                "trg_prevent_status_history_update_or_delete",
                "trg_transfer_ledger_invariants_transfers",
                "trg_transfer_ledger_invariants_ledger"
        );
    }
}
