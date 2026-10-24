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
        // Clear mutable tables between tests in clean dependency order
        jdbcTemplate.execute("DELETE FROM idempotency_records");
        jdbcTemplate.execute("DELETE FROM transfer_status_history");
        jdbcTemplate.execute("DELETE FROM processing_attempts");
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM processed_messages");
        jdbcTemplate.execute("DELETE FROM reconciliation_issues");
        jdbcTemplate.execute("DELETE FROM reconciliation_runs");
        jdbcTemplate.execute("DELETE FROM transfers");
        jdbcTemplate.execute("DELETE FROM accounts");
    }
}
