package com.hierarchyhub.producer;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class KafkaProducerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaProducer kafkaProducer = new KafkaProducer(kafkaTemplate);

    @Test
    public void pushSerializesAndSendsWithoutAKey() {
        HierarchyEvent event = linkEvent();

        kafkaProducer.push("org-hierarchy-edges", event);

        verify(kafkaTemplate).send(anyString(), contains("\"orgId\":\"org-1\""));
    }

    @Test
    public void pushWithKeySerializesAndSendsWithTheGivenKey() {
        HierarchyEvent event = linkEvent();

        kafkaProducer.pushWithKey("org-hierarchy-edges", event, "org-1");

        verify(kafkaTemplate).send(anyString(), anyString(), contains("\"orgId\":\"org-1\""));
    }

    @Test
    public void pushNeverThrowsWhenSerializationFails() {
        kafkaProducer.push("org-hierarchy-edges", new UnserializableBean());

        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    /** A bean whose getter always throws, so Jackson always fails serialization with a {@code JsonProcessingException}. */
    private static final class UnserializableBean {
        public String getValue() {
            throw new RuntimeException("boom");
        }
    }

    private HierarchyEvent linkEvent() {
        return new HierarchyEvent("org-1", "parent-1", null, "Org One", false,
                Constants.EVENT_TYPE_LINK, System.currentTimeMillis(), "user-1");
    }
}
