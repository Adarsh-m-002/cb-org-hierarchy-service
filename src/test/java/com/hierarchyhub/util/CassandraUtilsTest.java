package com.hierarchyhub.util;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.model.OrganisationRecord;
import org.igot.common.ApiResponse;
import org.igot.common.cassandra.CassandraOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CassandraUtilsTest {

    private final FakeCassandraOperation cassandraOperation = new FakeCassandraOperation();
    private final CassandraUtils cassandraUtils = new CassandraUtils(cassandraOperation);

    @BeforeEach
    public void setUp() {
        cassandraOperation.rows.clear();
        ReflectionTestUtils.setField(cassandraUtils, "cassandraFetchChunkSize", 500);
    }

    @Test
    public void fetchesAndMapsEveryRequestedIdThatExists() {
        cassandraOperation.put("org-1", "Org One", true, "ministry-1", 128, "state");
        cassandraOperation.put("org-2", "Org Two", false, "ministry-1", 16, "SPV");

        Map<String, OrganisationRecord> result = cassandraUtils.fetchBatchOrganisationsFromTable(Arrays.asList("org-1", "org-2", "org-missing"));

        assertEquals(2, result.size());
        assertEquals("Org One", result.get("org-1").name());
        assertTrue(result.get("org-1").active());
        assertEquals("Org Two", result.get("org-2").name());
        assertEquals(false, result.get("org-2").active());
        assertEquals(16, result.get("org-2").organizationType());
    }

    @Test
    public void returnsEmptyForNoIds() {
        Map<String, OrganisationRecord> result = cassandraUtils.fetchBatchOrganisationsFromTable(Collections.emptyList());

        assertTrue(result.isEmpty());
    }

    @Test
    public void chunksLargeIdListsAcrossMultipleCalls() {
        ReflectionTestUtils.setField(cassandraUtils, "cassandraFetchChunkSize", 2);
        for (int i = 1; i <= 5; i++) {
            cassandraOperation.put("org-" + i, "Org " + i, true, "ministry-1", 128, "state");
        }

        Map<String, OrganisationRecord> result = cassandraUtils.fetchBatchOrganisationsFromTable(
                Arrays.asList("org-1", "org-2", "org-3", "org-4", "org-5"));

        assertEquals(5, result.size());
        // 5 ids at a chunk size of 2 -> 3 round-trips (2 + 2 + 1), never one call for all 5
        assertEquals(3, cassandraOperation.callCount);
    }

    @Test
    public void neverHangsWhenTheChunkSizeIsUnsetOrZero() {
        ReflectionTestUtils.setField(cassandraUtils, "cassandraFetchChunkSize", 0);
        cassandraOperation.put("org-1", "Org One", true, "ministry-1", 128, "state");

        Map<String, OrganisationRecord> result = cassandraUtils.fetchBatchOrganisationsFromTable(Collections.singletonList("org-1"));

        assertEquals(1, result.size());
    }

    /** A tiny in-memory stand-in for common-util's {@link CassandraOperation}, so these tests need no database. */
    private static final class FakeCassandraOperation implements CassandraOperation {

        private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        private int callCount;

        void put(String id, String name, boolean active, String ministryId, Integer organizationType, String ministryOrStateType) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(Constants.ID, id);
            row.put(Constants.ORGNAME, name);
            row.put(Constants.STATUS, active ? Constants.ACTIVE_STATUS : 0);
            row.put(Constants.MINISTRYORSTATEID, ministryId);
            row.put(Constants.ORGANISATIONTYPE, organizationType);
            row.put(Constants.MINISTRYORSTATETYPE, ministryOrStateType);
            rows.put(id, row);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> getRecordsByProperties(String keyspaceName, String tableName,
                                                                  Map<String, Object> propertyMap, List<String> fields, Integer limit) {
            callCount++;
            Object idValue = propertyMap.get(Constants.ID);
            List<String> ids = idValue instanceof List ? (List<String>) idValue : Collections.singletonList((String) idValue);
            List<Map<String, Object>> result = new ArrayList<>();
            for (String id : ids) {
                if (rows.containsKey(id)) {
                    result.add(rows.get(id));
                }
            }
            return result;
        }

        @Override
        public Object insertRecord(String keyspaceName, String tableName, Map<String, Object> request) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public List<Map<String, Object>> getAllRecordsByProperties(String keyspaceName, String tableName,
                                                                     Map<String, Object> primaryKey, List<String> fields, int pageSize) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public Map<String, Object> updateRecord(String keyspaceName, String tableName, Map<String, Object> updateAttributes,
                                                 Map<String, Object> compositeKey) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public ApiResponse insertBulkRecord(String keyspaceName, String tableName, List<Map<String, Object>> request) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public void deleteRecord(String keyspaceName, String tableName, Map<String, Object> keyMap) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public Object insertRecord(String keyspaceName, String tableName, String primaryKeyColumn, String primaryKeyValue,
                                    Map<String, Object> compositeKey, Map<String, Object> otherFields) {
            throw new UnsupportedOperationException("not used by these tests");
        }
    }
}
