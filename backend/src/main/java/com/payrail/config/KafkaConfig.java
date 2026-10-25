package com.payrail.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${payrail.topics.transfer-commands:payrail.transfer-commands.v1}")
    private String transferCommandsTopic;

    @Value("${payrail.topics.transfer-commands-dlq:payrail.transfer-commands.dlq.v1}")
    private String transferCommandsDlqTopic;

    @Bean
    public NewTopic transferCommandsTopic() {
        return TopicBuilder.name(transferCommandsTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transferCommandsDlqTopic() {
        return TopicBuilder.name(transferCommandsDlqTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public org.springframework.kafka.listener.CommonErrorHandler kafkaListenerErrorHandler() {
        org.springframework.kafka.listener.DefaultErrorHandler errorHandler =
                new org.springframework.kafka.listener.DefaultErrorHandler(
                        new com.payrail.consumer.service.DurableRecoveryEnforcingRecordRecoverer(250L),
                        new org.springframework.util.backoff.FixedBackOff(250L, 2L)
                );
        errorHandler.setResetStateOnRecoveryFailure(true);
        errorHandler.setCommitRecovered(false);
        return errorHandler;
    }
}
