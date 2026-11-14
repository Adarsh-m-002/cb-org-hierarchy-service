package com.hierarchyhub.config;

import com.hierarchyhub.dto.Constants;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class BulkUploadConsumerConfig {

    private final KafkaProperties kafkaProperties;

    @Value("${org-hierarchy.bulk.upload.kafka.consumer-group}")
    private String consumerGroup;

    @Value("${org-hierarchy.bulk.upload.kafka.concurrency}")
    private int concurrency;

    public BulkUploadConsumerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ConsumerFactory<String, String> bulkUploadConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, kafkaProperties.getAutoCommitIntervalMs());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaProperties.getOffsetResetValue());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, kafkaProperties.getMaxPollIntervalMs());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean(Constants.BULK_UPLOAD_KAFKA_LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> bulkUploadListenerContainerFactory(
            ConsumerFactory<String, String> bulkUploadConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bulkUploadConsumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setPollTimeout(kafkaProperties.getConsumerPollTimeoutMs());
        return factory;
    }
}
