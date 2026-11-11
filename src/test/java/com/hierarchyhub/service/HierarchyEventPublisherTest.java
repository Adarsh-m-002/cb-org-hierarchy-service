package com.hierarchyhub.service;

import com.hierarchyhub.config.KafkaProperties;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyEvent;
import com.hierarchyhub.producer.KafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

public class HierarchyEventPublisherTest {

    private final KafkaProducer kafkaProducer = mock(KafkaProducer.class);
    private final KafkaProperties kafkaProperties = new KafkaProperties();
    private HierarchyEventPublisher hierarchyEventPublisher;

    @BeforeEach
    public void setUp() {
        kafkaProperties.setEdgesTopic("org-hierarchy-edges");
        kafkaProperties.setPublisherCorePoolSize(1);
        kafkaProperties.setPublisherMaxPoolSize(1);
        kafkaProperties.setPublisherQueueCapacity(10);
        kafkaProperties.setPublisherKeepAliveSeconds(30L);
        hierarchyEventPublisher = new HierarchyEventPublisher(kafkaProducer, kafkaProperties);
    }

    @Test
    public void publishAsyncDispatchesToTheEdgesTopicKeyedByOrgId() {
        HierarchyEvent event = linkEvent("org-1");

        hierarchyEventPublisher.publishAsync(event);

        verify(kafkaProducer, timeout(2000)).pushWithKey("org-hierarchy-edges", event, "org-1");
    }

    @Test
    public void publishAsyncIgnoresANullEvent() {
        hierarchyEventPublisher.publishAsync(null);

        verify(kafkaProducer, never()).pushWithKey(anyString(), eq(null), anyString());
    }

    @Test
    public void publishAsyncIgnoresAnEventWithNoOrgId() {
        hierarchyEventPublisher.publishAsync(linkEvent(null));

        verify(kafkaProducer, never()).pushWithKey(anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    public void publishBatchAsyncDispatchesEveryEventKeyedByItsOwnOrgId() {
        List<HierarchyEvent> events = Arrays.asList(linkEvent("org-1"), linkEvent("org-2"));

        hierarchyEventPublisher.publishBatchAsync(events);

        verify(kafkaProducer, timeout(2000)).pushWithKey("org-hierarchy-edges", events.get(0), "org-1");
        verify(kafkaProducer, timeout(2000)).pushWithKey("org-hierarchy-edges", events.get(1), "org-2");
    }

    @Test
    public void publishBatchAsyncIgnoresAnEmptyCollection() {
        hierarchyEventPublisher.publishBatchAsync(List.of());

        verify(kafkaProducer, never()).pushWithKey(anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    private HierarchyEvent linkEvent(String orgId) {
        return new HierarchyEvent(orgId, "parent-1", null, "Org", false,
                Constants.EVENT_TYPE_LINK, System.currentTimeMillis(), "user-1");
    }
}
