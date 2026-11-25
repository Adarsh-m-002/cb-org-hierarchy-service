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
import org.springframework.kafka.support.Acknowledgment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
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

        listener.onBatch(records, acknowledgment);

        verify(orgUpdateService, times(1)).resyncSingleOrgParentPath(eq("org-1"), anyString());
        verify(orgUpdateService, times(1)).resyncSingleOrgParentPath(eq("org-2"), anyString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void attributesTheResyncToTheTriggeringEventsUserId() {
        List<ConsumerRecord<String, String>> records = Collections.singletonList(
                record("org-1", "{\"userId\":\"user-42\"}"));

        listener.onBatch(records, acknowledgment);

        verify(orgUpdateService, times(1)).resyncSingleOrgParentPath("org-1", "user-42");
    }

    @Test
    public void fallsBackToTheSystemUserWhenTheEventHasNoUserId() {
        List<ConsumerRecord<String, String>> records = Collections.singletonList(record("org-1", "{}"));

        listener.onBatch(records, acknowledgment);

        verify(orgUpdateService, times(1)).resyncSingleOrgParentPath("org-1", Constants.SYSTEM_USER);
    }

    @Test
    public void fallsBackToParsingTheOrgIdFromThePayloadWhenTheKeyIsBlank() {
        List<ConsumerRecord<String, String>> records = Collections.singletonList(
                record(null, "{\"orgId\":\"org-9\",\"eventType\":\"LINK\"}"));

        listener.onBatch(records, acknowledgment);

        verify(orgUpdateService, times(1)).resyncSingleOrgParentPath(eq("org-9"), anyString());
    }

    @Test
    public void ignoresARecordWhoseKeyIsBlankAndPayloadIsUnparseable() {
        List<ConsumerRecord<String, String>> records = Collections.singletonList(record("", "not-json"));

        listener.onBatch(records, acknowledgment);

        verify(orgUpdateService, never()).resyncSingleOrgParentPath(anyString(), anyString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void doesNothingForAnEmptyBatch() {
        listener.onBatch(Collections.emptyList(), acknowledgment);

        verify(orgUpdateService, never()).resyncSingleOrgParentPath(anyString(), anyString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void publishesAFailureAuditWhenProjectionThrows() {
        when(orgUpdateService.resyncSingleOrgParentPath(eq("org-1"), anyString())).thenThrow(new RuntimeException("db down"));

        listener.onBatch(Collections.singletonList(record("org-1", "{}")), acknowledgment);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).push(eq("org-hierarchy-failed-error"), payloadCaptor.capture());
        com.hierarchyhub.dto.HierarchyEvent failureAudit = (com.hierarchyhub.dto.HierarchyEvent) payloadCaptor.getValue();
        assertEquals("org-1", failureAudit.getOrgId());
        assertEquals(Constants.PROJECTION_FAILED_PREFIX + "db down", failureAudit.getEventType());
        assertEquals(Constants.SYSTEM_USER, failureAudit.getUserId());
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void doesNotPublishAFailureAuditWhenProjectionSucceeds() {
        when(orgUpdateService.resyncSingleOrgParentPath(eq("org-1"), anyString())).thenReturn(mock(SyncParentPathResponse.class));

        listener.onBatch(Collections.singletonList(record("org-1", "{}")), acknowledgment);

        verify(kafkaProducer, never()).push(anyString(), any());
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("org-hierarchy-edges", 0, 0L, key, value);
    }
}
