package com.payrail;

import com.payrail.consumer.domain.DeadLetterEnvelope;
import com.payrail.consumer.service.DeadLetterPublisher;
import com.payrail.outbox.domain.OutboxEventRecord;
import com.payrail.outbox.repository.OutboxRepository;
import com.payrail.outbox.service.OutboxPublisherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class DlqConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DeadLetterPublisher deadLetterPublisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    @Test
    @DisplayName("Finding 4: Concurrent DLQ records for the exact same transfer allocate unique aggregate identities without sequence collisions")
    void shouldPersistConcurrentDlqRecordsForSameTransferWithoutSequenceCollisions() throws Exception {
        UUID transferId = UUID.randomUUID();
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<UUID>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            tasks.add(() -> transactionTemplate.execute(status -> {
                DeadLetterEnvelope dlq = new DeadLetterEnvelope(
                        transferCommandsTopic,
                        UUID.randomUUID(),
                        transferId,
                        "CONCURRENT_DLQ_TEST",
                        "Concurrent DLQ error message " + index,
                        index + 1,
                        Instant.now(),
                        "{\"raw\":\"concurrent-payload-" + index + "\"}"
                );
                return deadLetterPublisher.recordDeadLetter(dlq);
            }));
        }

        // Run all 20 threads simultaneously
        List<Future<UUID>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        List<UUID> eventIds = new ArrayList<>();
        for (Future<UUID> future : futures) {
            UUID eventId = future.get();
            assertThat(eventId).isNotNull();
            eventIds.add(eventId);
        }

        // Verify all 20 event IDs are unique
        assertThat(eventIds).hasSize(threadCount);
        assertThat(eventIds.stream().distinct().count()).isEqualTo(threadCount);

        // Verify all 20 DLQ events exist in outbox_events with aggregate_type = 'dlq' and correlation_id = transferId
        List<OutboxEventRecord> allUnpublished = outboxRepository.findUnpublished(Instant.now().plusSeconds(3600));
        List<OutboxEventRecord> dlqEvents = allUnpublished.stream()
                .filter(e -> "transfer.dlq".equals(e.eventType()) && transferId.equals(e.correlationId()))
                .toList();

        assertThat(dlqEvents).hasSize(threadCount);
        assertThat(dlqEvents).allMatch(e -> "dlq".equals(e.aggregateType()));
        assertThat(dlqEvents).allMatch(e -> e.aggregateSequence() == 1L);
        assertThat(dlqEvents.stream().map(OutboxEventRecord::aggregateId).distinct().count()).isEqualTo(threadCount);

        // Verify leased publisher can claim and publish all 20 DLQ events to Kafka topic
        int published = outboxPublisherService.publishDueEvents();
        assertThat(published).isGreaterThanOrEqualTo(threadCount);

        for (UUID eventId : eventIds) {
            OutboxEventRecord publishedEvent = outboxRepository.findById(eventId).orElseThrow();
            assertThat(publishedEvent.publishedAt()).isNotNull();
        }
    }
}
