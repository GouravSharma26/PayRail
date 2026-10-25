package com.payrail;

import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.consumer.service.DeadLetterPublisher;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.outbox.service.OutboxPublisherService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "payrail.processing.consumer-enabled=true"
})
@DirtiesContext
class KafkaListenerContainerErrorPolicyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DeadLetterPublisher deadLetterPublisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    @Value("${spring.kafka.consumer.group-id:payrail-transfer-workers}")
    private String consumerGroupId;

    @AfterEach
    void tearDown() {
        deadLetterPublisher.setFailureInjector(null);
        if (kafkaListenerEndpointRegistry != null) {
            kafkaListenerEndpointRegistry.stop();
        }
    }

    @Test
    @DisplayName("Finding 1: Database failure during malformed message recovery refuses offset commit, and recovers cleanly upon database recovery")
    void shouldRefuseOffsetCommitOnDatabaseFailureAndRecoverWhenDatabaseRestored() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String malformedMessage = "{\"invalid_json_payload\":\"" + correlationId;
        double dlqCountBefore = meterRegistry.counter("payrail.consumer.events_dlq").count();

        // 1. Inject simulated database failure during dead-letter persistence
        deadLetterPublisher.setFailureInjector(dlq -> {
            throw new DataAccessResourceFailureException("Simulated database outage during DLQ persistence");
        });

        // 2. Publish malformed message to transfer-commands topic
        var sendResult = kafkaTemplate.send(transferCommandsTopic, correlationId, malformedMessage).get(5, TimeUnit.SECONDS);
        int targetPartition = sendResult.getRecordMetadata().partition();
        long targetOffset = sendResult.getRecordMetadata().offset();
        TopicPartition topicPartition = new TopicPartition(transferCommandsTopic, targetPartition);

        // 3. Wait through configured container retry attempts while DB is unavailable
        Thread.sleep(2000L);

        // Verify no DLQ outbox record was written during database failure
        List<OutboxEventRecord> duringFailureEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(60))
                .stream()
                .filter(e -> "transfer.dlq".equals(e.eventType()))
                .toList();
        assertThat(duringFailureEvents).isEmpty();
        // A failed durable DLQ write must not be counted as a completed DLQ outcome.
        assertThat(meterRegistry.counter("payrail.consumer.events_dlq").count())
                .isEqualTo(dlqCountBefore);

        // 4. Verify Kafka consumer group has NOT committed offset past the target offset
        try (AdminClient adminClient = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()
        ))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = adminClient
                    .listConsumerGroupOffsets(consumerGroupId)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);

            OffsetAndMetadata committed = offsets.get(topicPartition);
            if (committed != null) {
                // If an offset was committed, it must NOT be past targetOffset (targetOffset + 1 would mean skipped/acknowledged)
                assertThat(committed.offset()).isLessThanOrEqualTo(targetOffset);
            }
        }

        // 5. Restore database availability
        deadLetterPublisher.setFailureInjector(null);

        // 6. Verify listener container redelivers the message and durably records the DLQ event in outbox_events
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<OutboxEventRecord> dlqEvents = outboxRepository.findUnpublished(Instant.now().plusSeconds(60))
                    .stream()
                    .filter(e -> "transfer.dlq".equals(e.eventType()))
                    .toList();
            assertThat(dlqEvents).isNotEmpty();
            assertThat(dlqEvents.get(0).payloadJson()).contains("MALFORMED_EVENT");
            assertThat(dlqEvents.get(0).payloadJson()).contains(correlationId);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(meterRegistry.counter("payrail.consumer.events_dlq").count())
                        .isEqualTo(dlqCountBefore + 1.0));

        // 7. Verify outbox publisher successfully publishes the DLQ record to the Kafka DLQ topic
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(1);

        // 8. Verify Kafka consumer offset is now committed past targetOffset
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try (AdminClient adminClient = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()
            ))) {
                Map<TopicPartition, OffsetAndMetadata> offsets = adminClient
                        .listConsumerGroupOffsets(consumerGroupId)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);

                OffsetAndMetadata committed = offsets.get(topicPartition);
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isGreaterThan(targetOffset);
            }
        });
    }
}
