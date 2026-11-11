package com.hierarchyhub.service;

import com.hierarchyhub.config.KafkaProperties;
import com.hierarchyhub.dto.HierarchyEvent;
import com.hierarchyhub.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Publishes hierarchy mutation events to {@code org-hierarchy-edges} on a bounded background pool - never on the caller's request thread. */
@Service
public class HierarchyEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HierarchyEventPublisher.class);

    private final KafkaProducer kafkaProducer;
    private final KafkaProperties kafkaProperties;
    private final ExecutorService publishExecutor;

    public HierarchyEventPublisher(KafkaProducer kafkaProducer, KafkaProperties kafkaProperties) {
        this.kafkaProducer = kafkaProducer;
        this.kafkaProperties = kafkaProperties;
        this.publishExecutor = new ThreadPoolExecutor(
                kafkaProperties.getPublisherCorePoolSize(),
                kafkaProperties.getPublisherMaxPoolSize(),
                kafkaProperties.getPublisherKeepAliveSeconds(), TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(kafkaProperties.getPublisherQueueCapacity()),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Publishes one hierarchy event asynchronously, keyed by its own {@code orgId}. */
    public void publishAsync(HierarchyEvent event) {
        if (event == null || event.getOrgId() == null) {
            return;
        }
        publishExecutor.execute(() -> {
            try {
                kafkaProducer.pushWithKey(kafkaProperties.getEdgesTopic(), event, event.getOrgId());
                log.info("Published hierarchy event: eventType={}, orgId={}, parentId={}, prevParentId={}",
                        event.getEventType(), event.getOrgId(), event.getParentId(), event.getPrevParentId());
            } catch (Exception e) {
                log.error("Failed to publish hierarchy event for orgId={}: {}", event.getOrgId(), e.getMessage(), e);
            }
        });
    }

    /** Publishes a batch of hierarchy events asynchronously, each still keyed by its own {@code orgId}. */
    public void publishBatchAsync(Collection<HierarchyEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        publishExecutor.execute(() -> {
            for (HierarchyEvent event : events) {
                try {
                    kafkaProducer.pushWithKey(kafkaProperties.getEdgesTopic(), event, event.getOrgId());
                } catch (Exception e) {
                    log.error("Failed to publish batch event for orgId={}: {}", event.getOrgId(), e.getMessage(), e);
                }
            }
            log.info("Published batch of {} hierarchy events to topic {}", events.size(), kafkaProperties.getEdgesTopic());
        });
    }
}
