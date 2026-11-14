package com.hierarchyhub.repository;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import org.igot.common.ApiResponse;
import org.igot.common.cassandra.CassandraOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class BulkUploadCassandraRepository {

    private static final Logger log = LoggerFactory.getLogger(BulkUploadCassandraRepository.class);

    private final CassandraOperation cassandraOperation;

    public BulkUploadCassandraRepository(CassandraOperation cassandraOperation) {
        this.cassandraOperation = cassandraOperation;
    }

    public void insertInitiatedJob(String rootOrgId, String identifier, String fileName, String objectKey, String createdBy) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(Constants.ROOT_ORG_ID, rootOrgId);
        record.put(Constants.IDENTIFIER, identifier);
        record.put(Constants.FILE_NAME, fileName);
        record.put(Constants.FILE_PATH, objectKey);
        record.put(Constants.DATE_CREATED_ON, Instant.now());
        record.put(Constants.STATUS, Constants.INITIATED);
        record.put(Constants.CREATED_BY_CAMEL, createdBy);

        Object result;
        try {
            result = cassandraOperation.insertRecord(Constants.CASSANDRA_KEYSPACE, Constants.CASSANDRA_BULK_UPLOAD_TABLE, record);
        } catch (Exception e) {
            throw cassandraInsertFailure(rootOrgId, identifier, e.getMessage());
        }
        if (result instanceof ApiResponse apiResponse && !Constants.SUCCESS.equalsIgnoreCase(String.valueOf(apiResponse.get(Constants.RESPONSE)))) {
            throw cassandraInsertFailure(rootOrgId, identifier, String.valueOf(apiResponse.get(Constants.RESPONSE)));
        }
    }

    private static ServiceException cassandraInsertFailure(String rootOrgId, String identifier, String reason) {
        log.error("bulkLink Cassandra insert failed: rootOrgId={}, identifier={}, reason={}", rootOrgId, identifier, reason);
        return new ServiceException(ErrorCode.DEPENDENCY_FAILURE,
                "Could not record the bulk-link job in Cassandra (" + Constants.CASSANDRA_BULK_UPLOAD_TABLE + "): " + reason);
    }

    /** Marks the job {@code IN-PROGRESS} once the Kafka consumer actually starts processing it. */
    public void markInProgress(String rootOrgId, String identifier) {
        updateStatus(rootOrgId, identifier, Constants.IN_PROGRESS, null, null, null);
    }

    /** Marks the job terminal ({@code SUCCESSFUL}/{@code FAILED}) with its final row counts, once the consumer finishes. */
    public void markTerminal(String rootOrgId, String identifier, String status, int totalRecords, int successfulRecords, int failedRecords) {
        updateStatus(rootOrgId, identifier, status, totalRecords, successfulRecords, failedRecords);
    }

    private void updateStatus(String rootOrgId, String identifier, String status, Integer totalRecords, Integer successfulRecords, Integer failedRecords) {
        Map<String, Object> updateAttributes = new LinkedHashMap<>();
        updateAttributes.put(Constants.STATUS, status);
        if (totalRecords != null) {
            updateAttributes.put(Constants.TOTAL_RECORDS, totalRecords);
        }
        if (successfulRecords != null) {
            updateAttributes.put(Constants.SUCCESSFUL_RECORDS_COUNT, successfulRecords);
        }
        if (failedRecords != null) {
            updateAttributes.put(Constants.FAILED_RECORDS_COUNT, failedRecords);
        }
        updateAttributes.put(Constants.DATE_UPDATED_ON, Instant.now());

        updateRecord(rootOrgId, identifier, updateAttributes);
    }

    /** Points the job's {@code filePath} at the generated result workbook's object key, alongside its final status/counts, in one write. */
    public void markTerminalWithResultFile(String rootOrgId, String identifier, String status, int totalRecords,
                                            int successfulRecords, int failedRecords, String resultObjectKey) {
        Map<String, Object> updateAttributes = new LinkedHashMap<>();
        updateAttributes.put(Constants.STATUS, status);
        updateAttributes.put(Constants.TOTAL_RECORDS, totalRecords);
        updateAttributes.put(Constants.SUCCESSFUL_RECORDS_COUNT, successfulRecords);
        updateAttributes.put(Constants.FAILED_RECORDS_COUNT, failedRecords);
        updateAttributes.put(Constants.FILE_PATH, resultObjectKey);
        updateAttributes.put(Constants.DATE_UPDATED_ON, Instant.now());

        updateRecord(rootOrgId, identifier, updateAttributes);
    }

    /** {@code CassandraOperation.updateRecord} both returns a FAILED map AND rethrows on failure (per its own contract) - caught here so a progress-write failure never fails the whole bulk-link job. */
    private void updateRecord(String rootOrgId, String identifier, Map<String, Object> updateAttributes) {
        Map<String, Object> compositeKey = new LinkedHashMap<>();
        compositeKey.put(Constants.ROOT_ORG_ID_LOWER, rootOrgId);
        compositeKey.put(Constants.IDENTIFIER, identifier);
        try {
            cassandraOperation.updateRecord(Constants.CASSANDRA_KEYSPACE, Constants.CASSANDRA_BULK_UPLOAD_TABLE, updateAttributes, compositeKey);
        } catch (Exception e) {
            log.error("bulkLink Cassandra update failed: rootOrgId={}, identifier={}, error={}", rootOrgId, identifier, e.getMessage(), e);
        }
    }
}
