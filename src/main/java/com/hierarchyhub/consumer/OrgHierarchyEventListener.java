package com.hierarchyhub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hierarchyhub.config.KafkaProperties;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyEvent;
import com.hierarchyhub.producer.KafkaProducer;
import com.hierarchyhub.service.OrgUpdateService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Consumes {@code org-hierarchy-edges}, deduplicates by orgId per batch, and re-projects each one's {@code parentPathId} from the current, authoritative PostgreSQL state. */
@Component
public class OrgHierarchyEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrgHierarchyEventListener.class);

    private final OrgUpdateService orgUpdateService;
    private final KafkaProducer kafkaProducer;
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrgHierarchyEventListener(OrgUpdateService orgUpdateService, KafkaProducer kafkaProducer, KafkaProperties kafkaProperties) {
        this.orgUpdateService = orgUpdateService;
        this.kafkaProducer = kafkaProducer;
        this.kafkaProperties = kafkaProperties;
    }

    @KafkaListener(topics = "${org.hierarchy.kafka.topic.edges}", groupId = "${org.hierarchy.kafka.consumer.group.id}",
            containerFactory = "kafkaBatchListenerContainerFactory")
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        if (records == null || records.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }
        log.info("OrgHierarchyEventListener received batch of {} records", records.size());

        Map<String, String> userIdsByOrgId = userIdsByOrgIdOf(records);
        log.info("Deduplicated batch to {} unique orgIds: {}", userIdsByOrgId.size(), userIdsByOrgId.keySet());

        for (Map.Entry<String, String> entry : userIdsByOrgId.entrySet()) {
            String orgId = entry.getKey();
            String userId = StringUtils.hasText(entry.getValue()) ? entry.getValue() : Constants.SYSTEM_USER;
            try {
                orgUpdateService.resyncParentPath(orgId, userId);
            } catch (Exception e) {
                log.error("Projection failed for orgId={}: {}", orgId, e.getMessage(), e);
                publishFailureAudit(orgId, e.getMessage());
            }
        }
        // Processing per orgId is fault-isolated above (caught, audited, and skipped) - nothing escapes this
        // method, so reaching here means the whole batch was handled and the offset can be committed.
        acknowledgment.acknowledge();
    }

    private Map<String, String> userIdsByOrgIdOf(List<ConsumerRecord<String, String>> records) {
        Map<String, String> userIdsByOrgId = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            if (StringUtils.hasText(record.key())) {
                userIdsByOrgId.put(record.key().trim(), parseUserId(record.value()));
                continue;
            }
            parseOrgId(record.value()).ifPresent(orgId -> userIdsByOrgId.put(orgId, parseUserId(record.value())));
        }
        return userIdsByOrgId;
    }

    private java.util.Optional<String> parseOrgId(String rawValue) {
        return parseEvent(rawValue).map(HierarchyEvent::getOrgId);
    }

    private String parseUserId(String rawValue) {
        return parseEvent(rawValue).map(HierarchyEvent::getUserId).orElse(null);
    }

    private java.util.Optional<HierarchyEvent> parseEvent(String rawValue) {
        try {
            return java.util.Optional.of(objectMapper.readValue(rawValue, HierarchyEvent.class));
        } catch (Exception e) {
            log.warn("Could not parse event payload: {}", rawValue, e);
            return java.util.Optional.empty();
        }
    }

    private void publishFailureAudit(String orgId, String error) {
        try {
            HierarchyEvent failureAudit = new HierarchyEvent();
            failureAudit.setOrgId(orgId);
            failureAudit.setEventType(Constants.PROJECTION_FAILED_PREFIX + error);
            failureAudit.setTimestamp(System.currentTimeMillis());
            failureAudit.setUserId(Constants.SYSTEM_USER);
            kafkaProducer.push(kafkaProperties.getFailedErrorTopic(), failureAudit);
        } catch (Exception e) {
            log.error("Could not publish failure audit for orgId={}: {}", orgId, e.getMessage(), e);
        }
    }
}
