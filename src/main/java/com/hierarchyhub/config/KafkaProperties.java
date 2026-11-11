package com.hierarchyhub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
public class KafkaProperties {

    @Value("${spring.kafka.bootstrap.servers}")
    private String bootstrapServers;

    @Value("${org.hierarchy.kafka.topic.edges}")
    private String edgesTopic;

    @Value("${org.hierarchy.kafka.topic.failed.error}")
    private String failedErrorTopic;

    @Value("${org.hierarchy.kafka.consumer.group.id}")
    private String consumerGroupId;

    @Value("${kafka.offset.reset.value}")
    private String offsetResetValue;

    @Value("${kafka.max.poll.interval.ms}")
    private Integer maxPollIntervalMs;

    @Value("${kafka.max.poll.records}")
    private Integer maxPollRecords;

    @Value("${kafka.auto.commit.interval.ms}")
    private Integer autoCommitIntervalMs;

    @Value("${kafka.consumer.concurrency}")
    private Integer consumerConcurrency;

    @Value("${kafka.consumer.poll.timeout.ms}")
    private Long consumerPollTimeoutMs;

    @Value("${kafka.producer.acks}")
    private String producerAcks;

    @Value("${kafka.producer.retries}")
    private Integer producerRetries;

    @Value("${kafka.producer.max.in.flight.requests}")
    private Integer producerMaxInFlightRequests;

    @Value("${org.hierarchy.kafka.publisher.core-pool-size}")
    private Integer publisherCorePoolSize;

    @Value("${org.hierarchy.kafka.publisher.max-pool-size}")
    private Integer publisherMaxPoolSize;

    @Value("${org.hierarchy.kafka.publisher.queue-capacity}")
    private Integer publisherQueueCapacity;

    @Value("${org.hierarchy.kafka.publisher.keep-alive-seconds}")
    private Long publisherKeepAliveSeconds;

    @Value("${org.hierarchy.kafka.publisher.timeout-ms}")
    private Long publisherTimeoutMs;

    @Value("${org.hierarchy.kafka.publisher.max-attempts}")
    private Integer publisherMaxAttempts;
}
