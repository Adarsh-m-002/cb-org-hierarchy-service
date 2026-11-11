package com.hierarchyhub.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Generic, domain-agnostic producer: serializes and pushes any object to any topic - reusable for any future topic, not just hierarchy events. */
@Service
public class KafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes a message to {@code topic} without a partition key. */
    public void push(String topic, Object value) {
        try {
            kafkaTemplate.send(topic, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message for topic {}: {}", topic, e.getMessage(), e);
        }
    }

    /** Publishes a message to {@code topic} keyed by {@code key} - same-key messages route to the same partition, guaranteeing FIFO order. */
    public void pushWithKey(String topic, Object value, String key) {
        try {
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message with key {} for topic {}: {}", key, topic, e.getMessage(), e);
        }
    }
}
