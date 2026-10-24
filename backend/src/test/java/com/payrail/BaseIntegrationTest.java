package com.payrail;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.8-alpine"))
                .withDatabaseName("payrail_test")
                .withUsername("test_user")
                .withPassword("test_secret");
        postgres.start();
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        // Reset all tables between tests without firing row-level application delete triggers
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    idempotency_records,
                    transfer_status_history,
                    processing_attempts,
                    ledger_entries,
                    outbox_events,
                    processed_messages,
                    reconciliation_issues,
                    reconciliation_runs,
                    transfers,
                    accounts
                CASCADE
                """);
    }

    protected void withTriggersDisabled(Runnable action) {
        jdbcTemplate.execute("ALTER TABLE transfers DISABLE TRIGGER trg_transfer_ledger_invariants_transfers");
        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_transfer_ledger_invariants_ledger");
        jdbcTemplate.execute("ALTER TABLE accounts DISABLE TRIGGER trg_account_balance_invariants_accounts");
        jdbcTemplate.execute("ALTER TABLE accounts DISABLE TRIGGER trg_protect_account_baseline_mutation");
        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_account_balance_invariants_ledger");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("ALTER TABLE transfers ENABLE TRIGGER trg_transfer_ledger_invariants_transfers");
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_transfer_ledger_invariants_ledger");
            jdbcTemplate.execute("ALTER TABLE accounts ENABLE TRIGGER trg_account_balance_invariants_accounts");
            jdbcTemplate.execute("ALTER TABLE accounts ENABLE TRIGGER trg_protect_account_baseline_mutation");
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_account_balance_invariants_ledger");
        }
    }
}
