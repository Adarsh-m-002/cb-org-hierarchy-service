package com.hierarchyhub.util;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.model.OrganisationRecord;
import org.igot.common.cassandra.CassandraOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class CassandraUtils {

    private final CassandraOperation cassandraOperation;

    /** Cassandra reads (organisation master) are chunked at this many ids per {@code getRecordsByProperties} call. */
    @Value("${org-hierarchy.cassandra.fetch-chunk-size}")
    private int cassandraFetchChunkSize;

    public CassandraUtils(CassandraOperation cassandraOperation) {
        this.cassandraOperation = cassandraOperation;
    }

    public Map<String, OrganisationRecord> fetchBatchOrganisationsFromTable(Collection<String> ids) {
        Map<String, OrganisationRecord> out = new LinkedHashMap<>();
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(ids));

        // never let a misconfigured/unset (e.g. 0 in a plain-construction unit test, bypassing @Value) chunk size
        // turn this into an infinite loop - always advance by at least 1 id per round-trip
        int chunkSize = Math.max(cassandraFetchChunkSize, 1);
        for (int start = 0; start < uniqueIds.size(); start += chunkSize) {
            List<String> chunk = uniqueIds.subList(start, Math.min(start + chunkSize, uniqueIds.size()));
            List<Map<String, Object>> rows = cassandraOperation.getRecordsByProperties(Constants.CASSANDRA_KEYSPACE,
                    Constants.CASSANDRA_ORGANISATION_TABLE, propertyMap(Constants.ID, new ArrayList<>(chunk)), null, chunk.size());
            for (Map<String, Object> row : rows) {
                OrganisationRecord record = toOrganisationRecord(row);
                out.put(record.id(), record);
            }
        }
        return out;
    }

    private static Map<String, Object> propertyMap(String column, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(column, value);
        return map;
    }

    private static OrganisationRecord toOrganisationRecord(Map<String, Object> row) {
        String id = (String) row.get(Constants.ID);
        String name = (String) row.get(Constants.ORGNAME);
        Number status = (Number) row.get(Constants.STATUS);
        String ministryId = (String) row.get(Constants.MINISTRYORSTATEID);
        Number orgType = (Number) row.get(Constants.ORGANISATIONTYPE);
        String ministryOrStateType = (String) row.get(Constants.MINISTRYORSTATETYPE);
        String parentPathId = (String) row.get(Constants.PARENTPATHID);
        boolean active = status != null && status.intValue() == Constants.ACTIVE_STATUS;
        return new OrganisationRecord(id, name, active, ministryId, orgType == null ? null : orgType.intValue(),
                ministryOrStateType, parentPathId);
    }
}
