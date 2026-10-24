package com.payrail.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.consumer.domain.DeadLetterEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${payrail.topics.transfer-commands-dlq:payrail.transfer-commands.dlq.v1}")
    private String dlqTopic;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(DeadLetterEnvelope dlqMessage) {
        try {
            String key = dlqMessage.transferId() != null
                    ? dlqMessage.transferId().toString()
                    : (dlqMessage.originalEventId() != null ? dlqMessage.originalEventId().toString() : "dlq");
            String payloadJson = objectMapper.writeValueAsString(dlqMessage);

            kafkaTemplate.send(dlqTopic, key, payloadJson).get(5, TimeUnit.SECONDS);
            log.warn("Published message to DLQ topic={} for originalEventId={} failureCode={}",
                    dlqTopic, dlqMessage.originalEventId(), dlqMessage.failureCode());
        } catch (Exception e) {
            log.error("Failed to publish dead-letter message to topic {}: {}", dlqTopic, e.getMessage(), e);
        }
    }
}
