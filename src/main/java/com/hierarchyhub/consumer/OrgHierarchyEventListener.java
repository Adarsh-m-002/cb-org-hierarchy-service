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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        log.info("OrgHierarchyEventListener received batch of {} records", records.size());

        Set<String> distinctOrgIds = distinctOrgIdsOf(records);
        log.info("Deduplicated batch to {} unique orgIds: {}", distinctOrgIds.size(), distinctOrgIds);

        for (String orgId : distinctOrgIds) {
            try {
                orgUpdateService.resyncParentPath(orgId);
            } catch (Exception e) {
                log.error("Projection failed for orgId={}: {}", orgId, e.getMessage(), e);
                publishFailureAudit(orgId, e.getMessage());
            }
        }
    }

    /**
     * One projection per unique {@code orgId} per poll, no matter how many events this batch carries for it - the
     * projection always recomputes from the current, authoritative PostgreSQL row, so running it once already
     * reflects every one of those events; a second run on the same batch would just be wasted work.
     */
    private Set<String> distinctOrgIdsOf(List<ConsumerRecord<String, String>> records) {
        Set<String> distinctOrgIds = new LinkedHashSet<>();
        for (ConsumerRecord<String, String> record : records) {
            if (StringUtils.hasText(record.key())) {
                distinctOrgIds.add(record.key().trim());
                continue;
            }
            parseOrgId(record.value()).ifPresent(distinctOrgIds::add);
        }
        return distinctOrgIds;
    }

    private java.util.Optional<String> parseOrgId(String rawValue) {
        try {
            HierarchyEvent event = objectMapper.readValue(rawValue, HierarchyEvent.class);
            return java.util.Optional.ofNullable(event.getOrgId());
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
