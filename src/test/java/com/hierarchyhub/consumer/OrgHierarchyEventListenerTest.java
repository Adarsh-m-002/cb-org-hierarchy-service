package com.hierarchyhub.consumer;

import com.hierarchyhub.config.KafkaProperties;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.SyncParentPathResponse;
import com.hierarchyhub.producer.KafkaProducer;
import com.hierarchyhub.service.OrgUpdateService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrgHierarchyEventListenerTest {

    private final OrgUpdateService orgUpdateService = mock(OrgUpdateService.class);
    private final KafkaProducer kafkaProducer = mock(KafkaProducer.class);
    private final KafkaProperties kafkaProperties = new KafkaProperties();
    private final OrgHierarchyEventListener listener = new OrgHierarchyEventListener(orgUpdateService, kafkaProducer, kafkaProperties);

    @BeforeEach
    public void setUp() {
        kafkaProperties.setFailedErrorTopic("org-hierarchy-failed-error");
    }

    @Test
    public void projectsOnceForEachDistinctOrgIdKeyedRecord() {
        List<ConsumerRecord<String, String>> records = Arrays.asList(
                record("org-1", "{}"),
                record("org-1", "{}"),
                record("org-2", "{}"));

        listener.onBatch(records);

        verify(orgUpdateService, times(1)).resyncParentPath("org-1");
        verify(orgUpdateService, times(1)).resyncParentPath("org-2");
    }

    @Test
    public void fallsBackToParsingTheOrgIdFromThePayloadWhenTheKeyIsBlank() {
        List<ConsumerRecord<String, String>> records = Collections.singletonList(
                record(null, "{\"orgId\":\"org-9\",\"eventType\":\"LINK\"}"));

        listener.onBatch(records);

        verify(orgUpdateService, times(1)).resyncParentPath("org-9");
    }

    @Test
    public void ignoresARecordWhoseKeyIsBlankAndPayloadIsUnparseable() {
        List<ConsumerRecord<String, String>> records = Collections.singletonList(record("", "not-json"));

        listener.onBatch(records);

        verify(orgUpdateService, never()).resyncParentPath(anyString());
    }

    @Test
    public void doesNothingForAnEmptyBatch() {
        listener.onBatch(Collections.emptyList());

        verify(orgUpdateService, never()).resyncParentPath(anyString());
    }

    @Test
    public void publishesAFailureAuditWhenProjectionThrows() {
        when(orgUpdateService.resyncParentPath("org-1")).thenThrow(new RuntimeException("db down"));

        listener.onBatch(Collections.singletonList(record("org-1", "{}")));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).push(eq("org-hierarchy-failed-error"), payloadCaptor.capture());
        com.hierarchyhub.dto.HierarchyEvent failureAudit = (com.hierarchyhub.dto.HierarchyEvent) payloadCaptor.getValue();
        assertEquals("org-1", failureAudit.getOrgId());
        assertEquals(Constants.PROJECTION_FAILED_PREFIX + "db down", failureAudit.getEventType());
        assertEquals(Constants.SYSTEM_USER, failureAudit.getUserId());
    }

    @Test
    public void doesNotPublishAFailureAuditWhenProjectionSucceeds() {
        when(orgUpdateService.resyncParentPath("org-1")).thenReturn(mock(SyncParentPathResponse.class));

        listener.onBatch(Collections.singletonList(record("org-1", "{}")));

        verify(kafkaProducer, never()).push(anyString(), org.mockito.ArgumentMatchers.any());
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("org-hierarchy-edges", 0, 0L, key, value);
    }
}
