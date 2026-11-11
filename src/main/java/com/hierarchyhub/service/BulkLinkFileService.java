package com.hierarchyhub.service;

import com.hierarchyhub.config.BulkLinkExecutorConfig;
import com.hierarchyhub.dto.BatchLinkExecutionResult;
import com.hierarchyhub.dto.BulkLinkJob;
import com.hierarchyhub.dto.BulkLinkRow;
import com.hierarchyhub.dto.BulkLinkRowFailure;
import com.hierarchyhub.dto.BulkLinkUploadResponse;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.OrgLinkErrorDetail;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.util.AppUtil;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.BulkLinkSheetReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class BulkLinkFileService {

    private static final Logger log = LoggerFactory.getLogger(BulkLinkFileService.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX").withZone(ZoneOffset.UTC);

    private final OrgHierarchyService orgHierarchyService;
    private final AuthUtils authUtils;
    private final Executor bulkLinkExecutor;

    @Value("${org-hierarchy.bulk.max-rows}")
    private int maxRows;

    @Value("${org-hierarchy.cassandra.fetch-chunk-size}")
    private int bulkLinkChunkSize;

    public BulkLinkFileService(OrgHierarchyService orgHierarchyService, AuthUtils authUtils,
                                @Qualifier(BulkLinkExecutorConfig.BULK_LINK_EXECUTOR) Executor bulkLinkExecutor) {
        this.orgHierarchyService = orgHierarchyService;
        this.authUtils = authUtils;
        this.bulkLinkExecutor = bulkLinkExecutor;
    }

    public ApiResponse initiate(MultipartFile file, String rootOrgId, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        if (file == null || file.isEmpty()) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Upload the Excel file in the form field 'file'");
        }
        if (AppUtil.isBlank(rootOrgId)) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "rootOrgId is required");
        }

        BulkLinkSheetReader.ParsedSheet parsedSheet = BulkLinkSheetReader.read(bytesOf(file));
        int totalRows = parsedSheet.rows().size() + parsedSheet.rowFailures().size();
        if (totalRows > maxRows) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST,
                    "At most " + maxRows + " rows per file; split it into smaller files");
        }

        BulkLinkJob bulkLinkJob = new BulkLinkJob(UUID.randomUUID().toString(), // log correlation only - never returned or polled
                file.getOriginalFilename(), userId, TIMESTAMP.format(Instant.now()), rootOrgId,
                totalRows, parsedSheet.duplicateChildRowsSkipped());
        parsedSheet.rowFailures().forEach(bulkLinkJob::recordFailure);
        log.info("bulkLink initiated: jobId={}, fileName={}, rootOrgId={}, totalRows={}, parseRowFailures={}, duplicateChildRowsSkipped={}",
                bulkLinkJob.getJobId(), bulkLinkJob.getFileName(), rootOrgId, totalRows,
                parsedSheet.rowFailures().size(), parsedSheet.duplicateChildRowsSkipped());

        try {
            bulkLinkExecutor.execute(() -> executeBulkLinkJob(bulkLinkJob, parsedSheet.rows(), userId));
        } catch (RejectedExecutionException e) {
            log.error("bulkLink queue full: jobId={}, fileName={}", bulkLinkJob.getJobId(), bulkLinkJob.getFileName());
            throw new ServiceException(ErrorCode.DEPENDENCY_FAILURE, "Bulk-link queue is full; retry later");
        }

        BulkLinkUploadResponse uploadResponse = new BulkLinkUploadResponse(bulkLinkJob.getFileName(), bulkLinkJob.getCreatedBy(),
                bulkLinkJob.getDateCreatedOn(), bulkLinkJob.getRootOrgId(), Constants.BULK_LINK_STATUS_INITIATED);
        return AppUtil.versionedResponse(Constants.API_BULK_LINK, uploadResponse);
    }

    private void executeBulkLinkJob(BulkLinkJob bulkLinkJob, List<BulkLinkRow> rows, String userId) {
        try {
            Map<String, List<BulkLinkRow>> rowsByParentOrganisationId = groupRowsByParentOrganisationId(rows);

            for (Map.Entry<String, List<BulkLinkRow>> entry : rowsByParentOrganisationId.entrySet()) {
                String parentOrgId = entry.getKey();
                List<BulkLinkRow> parentRows = entry.getValue();

                int chunkSize = Math.max(bulkLinkChunkSize, 1); // never allow a misconfigured/unset 0 to hang this loop
                for (int i = 0; i < parentRows.size(); i += chunkSize) {
                    List<BulkLinkRow> chunk = parentRows.subList(i, Math.min(i + chunkSize, parentRows.size()));
                    processParentChildrenChunk(bulkLinkJob, parentOrgId, chunk, userId);
                }
            }

            if (bulkLinkJob.getFailedRows() == 0) {
                log.info("bulkLink completed successfully: jobId={}, fileName={}, totalRows={}, succeededRows={}",
                        bulkLinkJob.getJobId(), bulkLinkJob.getFileName(), bulkLinkJob.getTotalRows(), bulkLinkJob.getSucceededRows());
            } else {
                log.warn("bulkLink completed with failures: jobId={}, fileName={}, totalRows={}, succeededRows={}, failedRows={}, failures={}",
                        bulkLinkJob.getJobId(), bulkLinkJob.getFileName(), bulkLinkJob.getTotalRows(),
                        bulkLinkJob.getSucceededRows(), bulkLinkJob.getFailedRows(), bulkLinkJob.getFailures());
            }
        } catch (Exception e) {
            log.error("bulkLink job unexpected failure: jobId={}, fileName={}", bulkLinkJob.getJobId(), bulkLinkJob.getFileName(), e);
        }
    }

    /** One parent, one chunk of its children (at most {@code org-hierarchy.cassandra.fetch-chunk-size}), one call into the shared batch engine. */
    private void processParentChildrenChunk(BulkLinkJob bulkLinkJob, String parentOrgId, List<BulkLinkRow> chunk, String userId) {
        List<String> childOrganisationIds = new ArrayList<>(chunk.size());
        Map<String, Integer> rowNumberByChildId = new HashMap<>(chunk.size());

        for (BulkLinkRow row : chunk) {
            childOrganisationIds.add(row.getChildOrgId());
            rowNumberByChildId.put(row.getChildOrgId(), row.getRowNumber());
        }

        try {
            BatchLinkExecutionResult executionResult = orgHierarchyService.linkChildrenUnderParentBatch(parentOrgId, childOrganisationIds, userId);

            for (int i = 0; i < executionResult.getSucceededChildOrganisationIds().size(); i++) {
                bulkLinkJob.recordSuccess();
            }
            for (OrgLinkErrorDetail failure : executionResult.getFailureErrors()) {
                Integer rowNumber = rowNumberByChildId.get(failure.getChildOrgId());
                bulkLinkJob.recordFailure(new BulkLinkRowFailure(rowNumber, parentOrgId, failure.getChildOrgId(), failure.getErrmsg()));
            }
        } catch (ServiceException e) {
            log.warn("bulkLink chunk failed for parentOrgId={}: {}", parentOrgId, e.getMessage());
            for (BulkLinkRow row : chunk) {
                bulkLinkJob.recordFailure(new BulkLinkRowFailure(row.getRowNumber(), parentOrgId, row.getChildOrgId(), e.getMessage()));
            }
        } catch (Exception e) {
            log.error("bulkLink chunk unexpected error for parentOrgId={}: {}", parentOrgId, e.getMessage(), e);
            for (BulkLinkRow row : chunk) {
                bulkLinkJob.recordFailure(new BulkLinkRowFailure(row.getRowNumber(), parentOrgId, row.getChildOrgId(), "System error: " + e.getMessage()));
            }
        }
    }

    private static Map<String, List<BulkLinkRow>> groupRowsByParentOrganisationId(List<BulkLinkRow> rows) {
        Map<String, List<BulkLinkRow>> rowsByParentOrganisationId = new LinkedHashMap<>();
        for (BulkLinkRow row : rows) {
            rowsByParentOrganisationId.computeIfAbsent(row.getParentOrgId(), k -> new ArrayList<>()).add(row);
        }
        return rowsByParentOrganisationId;
    }

    private static ByteArrayInputStream bytesOf(MultipartFile file) {
        try {
            return new ByteArrayInputStream(file.getBytes());
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Could not read the uploaded file: " + e.getMessage());
        }
    }
}
