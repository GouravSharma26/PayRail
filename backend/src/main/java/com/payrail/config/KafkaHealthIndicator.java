package com.payrail.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Custom health indicator that checks Kafka broker connectivity.
 * Included in the readiness probe group so the application is not routed traffic
 * until both PostgreSQL and Kafka are reachable.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(KafkaHealthIndicator.class);
    private static final long TIMEOUT_SECONDS = 5;

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeClusterResult cluster = adminClient.describeCluster();
            String clusterId = cluster.clusterId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int nodeCount = cluster.nodes().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size();

            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodeCount", nodeCount)
                    .build();
        } catch (Exception e) {
            log.warn("Kafka health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage() != null ? e.getMessage() : "Unable to reach Kafka broker")
                    .build();
        }
    }
}
