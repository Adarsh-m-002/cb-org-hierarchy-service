package com.hierarchyhub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hierarchyhub.dto.BatchLinkExecutionResult;
import com.hierarchyhub.dto.BulkLinkRow;
import com.hierarchyhub.dto.BulkLinkRowFailure;
import com.hierarchyhub.dto.BulkLinkUploadEvent;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.OrgLinkErrorDetail;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ParsedBulkLinkSheet;
import com.hierarchyhub.repository.BulkUploadCassandraRepository;
import com.hierarchyhub.service.OrgHierarchyService;
import com.hierarchyhub.util.ExcelUtils;
import com.hierarchyhub.util.CloudStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BulkLinkUploadConsumer {

    private static final Logger log = LoggerFactory.getLogger(BulkLinkUploadConsumer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CloudStorageService cloudStorageService;
    private final BulkUploadCassandraRepository bulkUploadCassandraRepository;
    private final OrgHierarchyService orgHierarchyService;

    @Value("${org-hierarchy.cassandra.fetch-chunk-size}")
    private int chunkSize;

    public BulkLinkUploadConsumer(CloudStorageService cloudStorageService, BulkUploadCassandraRepository bulkUploadCassandraRepository,
                                   OrgHierarchyService orgHierarchyService) {
        this.cloudStorageService = cloudStorageService;
        this.bulkUploadCassandraRepository = bulkUploadCassandraRepository;
        this.orgHierarchyService = orgHierarchyService;
    }

    @KafkaListener(topics = "${org-hierarchy.bulk.upload.kafka.topic}", groupId = "${org-hierarchy.bulk.upload.kafka.consumer-group}",
            containerFactory = Constants.BULK_UPLOAD_KAFKA_LISTENER_FACTORY)
    public void onBulkLinkUploadEvent(String message) {
        BulkLinkUploadEvent event;
        try {
            event = OBJECT_MAPPER.readValue(message, BulkLinkUploadEvent.class);
        } catch (Exception e) {
            log.error("bulkLink consumer: could not deserialize event: {}", e.getMessage(), e);
            return;
        }
        try {
            processJob(event);
        } catch (Exception e) {
            log.error("bulkLink consumer: job failed unexpectedly: rootOrgId={}, identifier={}, error={}",
                    event.getRootOrgId(), event.getIdentifier(), e.getMessage(), e);
            // Kafka auto-commits regardless, so this message is never redelivered - without this, the job would
            // stay IN-PROGRESS forever, invisible to sunbird-cb-ext's own progress endpoint as a failure
            markFailedBestEffort(event);
        }
    }

    private void markFailedBestEffort(BulkLinkUploadEvent event) {
        try {
            bulkUploadCassandraRepository.markTerminal(event.getRootOrgId(), event.getIdentifier(), Constants.STATUS_FAILED, 0, 0, 0);
        } catch (Exception e) {
            log.error("bulkLink consumer: could not even mark the job FAILED after its own failure: rootOrgId={}, identifier={}, error={}",
                    event.getRootOrgId(), event.getIdentifier(), e.getMessage(), e);
        }
    }

    private void processJob(BulkLinkUploadEvent event) {
        log.info("bulkLink consumer: starting job: rootOrgId={}, identifier={}, fileName={}", event.getRootOrgId(), event.getIdentifier(), event.getFileName());
        bulkUploadCassandraRepository.markInProgress(event.getRootOrgId(), event.getIdentifier());

        byte[] originalBytes = cloudStorageService.download(event.getObjectKey());
        ParsedBulkLinkSheet parsedSheet = ExcelUtils.read(new ByteArrayInputStream(originalBytes));

        Map<Integer, String> statusByRowNumber = new HashMap<>();
        Map<Integer, String> errorByRowNumber = new HashMap<>();
        for (BulkLinkRowFailure parseFailure : parsedSheet.rowFailures()) {
            statusByRowNumber.put(parseFailure.getRowNumber(), Constants.FAILED);
            errorByRowNumber.put(parseFailure.getRowNumber(), parseFailure.getReason());
        }

        int succeededRows = 0;
        int failedRows = parsedSheet.rowFailures().size();
        Map<String, List<BulkLinkRow>> rowsByParentOrganisationId = groupRowsByParentOrganisationId(parsedSheet.rows());
        for (Map.Entry<String, List<BulkLinkRow>> entry : rowsByParentOrganisationId.entrySet()) {
            String parentOrgId = entry.getKey();
            List<BulkLinkRow> parentRows = entry.getValue();
            int chunkLength = Math.max(chunkSize, 1); // never allow a misconfigured/unset 0 to hang this loop
            for (int i = 0; i < parentRows.size(); i += chunkLength) {
                List<BulkLinkRow> chunk = parentRows.subList(i, Math.min(i + chunkLength, parentRows.size()));
                int[] chunkCounts = processChunk(event, parentOrgId, chunk, statusByRowNumber, errorByRowNumber);
                succeededRows += chunkCounts[0];
                failedRows += chunkCounts[1];
            }
        }

        int totalRows = parsedSheet.rows().size() + parsedSheet.rowFailures().size();
        byte[] resultBytes = ExcelUtils.annotateResultWorkbook(originalBytes, statusByRowNumber, errorByRowNumber);
        String resultObjectKey = cloudStorageService.newObjectKey(Constants.RESULT_FILE_NAME_PREFIX + event.getFileName());
        cloudStorageService.upload(resultBytes, resultObjectKey);

        String finalStatus = failedRows > 0 ? Constants.STATUS_FAILED : Constants.SUCCESSFUL;
        bulkUploadCassandraRepository.markTerminalWithResultFile(event.getRootOrgId(), event.getIdentifier(), finalStatus,
                totalRows, succeededRows, failedRows, resultObjectKey);
        log.info("bulkLink consumer: completed job: rootOrgId={}, identifier={}, totalRows={}, succeededRows={}, failedRows={}, status={}",
                event.getRootOrgId(), event.getIdentifier(), totalRows, succeededRows, failedRows, finalStatus);
    }

    /** One parent, one chunk of its children - returns {succeeded, failed} row counts for this chunk. */
    private int[] processChunk(BulkLinkUploadEvent event, String parentOrgId, List<BulkLinkRow> chunk,
                                Map<Integer, String> statusByRowNumber, Map<Integer, String> errorByRowNumber) {
        List<String> childOrganisationIds = new ArrayList<>(chunk.size());
        Map<String, Integer> rowNumberByChildId = new HashMap<>(chunk.size());
        for (BulkLinkRow row : chunk) {
            childOrganisationIds.add(row.getChildOrgId());
            rowNumberByChildId.put(row.getChildOrgId(), row.getRowNumber());
        }

        try {
            BatchLinkExecutionResult executionResult = orgHierarchyService.linkChildrenUnderParentBatch(parentOrgId, childOrganisationIds, event.getCreatedBy());
            for (String succeededChildId : executionResult.getSucceededChildOrganisationIds()) {
                statusByRowNumber.put(rowNumberByChildId.get(succeededChildId), Constants.ROW_STATUS_SUCCESS);
            }
            for (OrgLinkErrorDetail failure : executionResult.getFailureErrors()) {
                Integer rowNumber = rowNumberByChildId.get(failure.getChildOrgId());
                statusByRowNumber.put(rowNumber, Constants.FAILED);
                errorByRowNumber.put(rowNumber, failure.getErrmsg());
            }
            return new int[] {executionResult.getSucceededChildOrganisationIds().size(), executionResult.getFailureErrors().size()};
        } catch (ServiceException e) {
            log.warn("bulkLink consumer: chunk failed for parentOrgId={}: {}", parentOrgId, e.getMessage());
            return markChunkFailed(chunk, statusByRowNumber, errorByRowNumber, e.getMessage());
        } catch (Exception e) {
            log.error("bulkLink consumer: chunk unexpected error for parentOrgId={}: {}", parentOrgId, e.getMessage(), e);
            return markChunkFailed(chunk, statusByRowNumber, errorByRowNumber, Constants.SYSTEM_ERROR_PREFIX + e.getMessage());
        }
    }

    private static int[] markChunkFailed(List<BulkLinkRow> chunk, Map<Integer, String> statusByRowNumber, Map<Integer, String> errorByRowNumber, String reason) {
        for (BulkLinkRow row : chunk) {
            statusByRowNumber.put(row.getRowNumber(), Constants.FAILED);
            errorByRowNumber.put(row.getRowNumber(), reason);
        }
        return new int[] {0, chunk.size()};
    }

    private static Map<String, List<BulkLinkRow>> groupRowsByParentOrganisationId(List<BulkLinkRow> rows) {
        Map<String, List<BulkLinkRow>> rowsByParentOrganisationId = new LinkedHashMap<>();
        for (BulkLinkRow row : rows) {
            rowsByParentOrganisationId.computeIfAbsent(row.getParentOrgId(), k -> new ArrayList<>()).add(row);
        }
        return rowsByParentOrganisationId;
    }
}
