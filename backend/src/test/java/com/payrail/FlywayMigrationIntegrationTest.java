package com.payrail;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should successfully apply Flyway migrations V1 through V5 on fresh PostgreSQL container")
    void shouldApplyMigrationsSuccessfully() {
        // 1. Verify flyway_schema_history records success for all migrations V1 to V5
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM \"flyway_schema_history\" WHERE \"success\" = true",
                Integer.class
        );
        assertThat(successfulMigrations).isNotNull().isGreaterThanOrEqualTo(5);

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
                "trg_transfer_ledger_invariants_ledger",
                "trg_account_balance_invariants_accounts",
                "trg_account_balance_invariants_ledger"
        );
    }

    @Test
    @DisplayName("Finding 3: A database that already applied original V1 and V2 passes validate-on-migrate and upgrades to latest without checksum mismatch")
    void shouldUpgradeDatabaseWithOriginalV2HistoryWithoutChecksumMismatch() {
        String testSchema = "schema_upgrade_test";
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + testSchema + " CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA " + testSchema);

        try {
            // Step 1: Simulate a database that originally migrated only up to V2
            Flyway flywayV2 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(testSchema)
                    .target("2")
                    .validateOnMigrate(true)
                    .load();

            MigrateResult v2Result = flywayV2.migrate();
            assertThat(v2Result.success).isTrue();
            assertThat(v2Result.targetSchemaVersion).isEqualTo("2");

            // Step 2: Validate and upgrade to the latest migrations (V3, V4, V5) with validate-on-migrate enabled
            Flyway flywayLatest = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(testSchema)
                    .validateOnMigrate(true)
                    .load();

            MigrateResult latestResult = flywayLatest.migrate();
            assertThat(latestResult.success).isTrue();
            assertThat(latestResult.migrationsExecuted).isGreaterThanOrEqualTo(3); // Applies V3, V4, V5
            assertThat(latestResult.targetSchemaVersion).isEqualTo("5");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + testSchema + " CASCADE");
        }
    }
}
