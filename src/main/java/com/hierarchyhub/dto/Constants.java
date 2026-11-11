package com.hierarchyhub.dto;

public class Constants {
    public static final String CHILDREN = "children";
    public static final String PARENT_OF = "parent_of";
    public static final String PARENT = "parent";
    public static final String ADDITIONAL_PROPERTIES_DOT = "additionalProperties.";
    public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    public static final String ID = "id";
    public static final String PARENT_ORG_ID   = "parentOrgId";
    public static final String ORGANIZATIONS   = "organizations";
    public static final String SUCCESSFUL = "Successful";
    public static final String FAILED = "Failed";
    public static final String MESSAGE = "message";
    public static final String RESPONSE = "response";
    public static final String SUCCESS = "success";

    // org-hierarchy v2 (link/move/unlink/read/hierarchy) response envelope api ids
    public static final String API_LINK = "api.org.hierarchy.link";
    public static final String API_MOVE = "api.org.hierarchy.move";
    public static final String API_UNLINK = "api.org.hierarchy.unlink";
    public static final String API_READ = "api.org.hierarchy.read";
    public static final String API_HIERARCHY = "api.org.hierarchy";
    public static final String API_BULK_LINK = "api.org.hierarchy.bulk.link";

    // /bulk/link sheet: a fixed sheet name and column headers, both matched case-insensitively
    public static final String BULK_LINK_SHEET_NAME = "referenceSheet";
    public static final String BULK_LINK_COLUMN_PARENT = "parentOrgId";
    public static final String BULK_LINK_COLUMN_CHILD = "childOrgId";
    // /bulk/link always responds at submission time, before any row is processed - this is the only status it ever reports
    public static final String BULK_LINK_STATUS_INITIATED = "INITIATED";

    // response envelope status used by the ApiResponse.success/failure factories (uppercase, distinct from the
    // pre-existing lowercase SUCCESS/FAILED above so no existing caller's behaviour changes)
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    // the caller's signed access token, verified in-service rather than trusted from an upstream-resolved header
    public static final String AUTH_TOKEN_HEADER = "x-authenticated-user-token";

    // one consistent message per outcome, instead of a different phrasing per code path that reaches the same outcome
    public static final String MSG_ORG_ADDED_TO_HIERARCHY = "Successfully added organisation to the hierarchy";
    public static final String MSG_ORG_REMOVED_FROM_HIERARCHY = "Successfully removed organisation from the hierarchy";
    public static final String MSG_ORG_MOVED_IN_HIERARCHY = "Successfully moved organisation to the hierarchy";

    // Cassandra organisation master (sunbird.organisation): keyspace/table and column names, named after the actual
    // column value, not a semantic alias - e.g. ORGNAME = "orgname", not NAME_COLUMN = "orgname"
    public static final String CASSANDRA_KEYSPACE = "sunbird";
    public static final String CASSANDRA_ORGANISATION_TABLE = "organisation";
    public static final String ORGNAME = "orgname";
    public static final String STATUS = "status";
    public static final String MINISTRYORSTATEID = "ministryorstateid";
    public static final String ORGANISATIONTYPE = "organisationtype";
    public static final String MINISTRYORSTATETYPE = "ministryorstatetype";
    public static final int ACTIVE_STATUS = 1;

    // the two hierarchy-type values a ministryOrStateType/hierarchyTypeOf ever returns
    public static final String HIERARCHY_TYPE_MINISTRY = "ministry";
    public static final String HIERARCHY_TYPE_STATE = "state";

    // org_hierarchy (PostgreSQL) columns read by OrgHierarchyRepository's row mapper
    public static final String NAME = "name";
    public static final String PARENT_ID = "parent_id";
    public static final String IDLE = "idle";
    public static final String CHILD_COUNT = "child_count";
    public static final String CREATED_AT = "created_at";
    public static final String CREATED_BY = "created_by";
    public static final String UPDATED_AT = "updated_at";
    public static final String UPDATED_BY = "updated_by";
    public static final String DEPTH = "depth";

    // org_hierarchy_level (PostgreSQL) columns read by OrgHierarchyLevelRepository's row mapper
    public static final String LEVEL = "level";
    public static final String DESCRIPTION = "description";

    // named-parameter key for the acting user on every org_hierarchy/org_hierarchy_level audit write
    // (created_by/updated_by), shared by OrgHierarchyRepository and OrgHierarchyLevelRepository
    public static final String PARAM_UPDATED_BY = "by";

    // org-hierarchy.ministry.missing policy values
    public static final String MINISTRY_MISSING_POLICY_ALLOW = "ALLOW";

    // POST /link: batch response envelope status/keys and per-rule rejection message templates (see
    // docs/link-api-optimization-and-spec.md for the full guard matrix each of these corresponds to)
    public static final String STATUS_PARTIAL_SUCCESS = "PARTIAL_SUCCESS";
    public static final String KEY_TOTAL_COUNT = "totalCount";
    public static final String KEY_SUCCESS_COUNT = "successCount";
    public static final String KEY_FAILED_COUNT = "failedCount";
    public static final String KEY_ERRORS = "errors";
    public static final String MSG_ORG_PARTIALLY_ADDED = "Partially added organisations to the hierarchy";
    public static final String MSG_ALL_ORGS_FAILED_TO_LINK = "Failed to add organisations to the hierarchy";
    public static final String MSG_FAILED_TO_LINK_N_OF_TOTAL = "Failed to link %d of %d organisations";

    public static final String ERR_CANNOT_BE_OWN_PARENT = "An org cannot be its own parent";
    public static final String ERR_ORG_NOT_FOUND_IN_TABLE = "Org %s not found in organisation table";
    public static final String ERR_ORG_IS_INACTIVE = "Org %s is inactive";
    public static final String ERR_ORG_IS_ROOT_L0 = "Org %s is a %s (organizationType %d): it cannot be attached under another org";
    public static final String ERR_HIERARCHY_TYPE_MISMATCH = "Org %s belongs to the %s hierarchy but parent %s belongs to the %s hierarchy";
    public static final String ERR_DYNAMIC_MINISTRY_MISMATCH = "Org %s belongs to %s %s but parent %s belongs to %s %s";
    public static final String ERR_ALREADY_HAS_PARENT = "Org %s is already under %s; use /move";
    public static final String ERR_CYCLE_DETECTED = "Org %s cannot be placed under %s: that is one of its own descendants";

    public static final String ERR_PARENT_NOT_FOUND_IN_TABLE = "Parent %s not found in organisation table";
    public static final String ERR_PARENT_IS_INACTIVE = "Parent %s is inactive";
    public static final String ERR_PARENT_NOT_IN_HIERARCHY = "Parent %s is not in the hierarchy: map it first (parents are mapped top-down)";

    // POST /unlink
    public static final String ERR_ORGANISATION_HAS_CHILDREN = "Organisation %s has active child organisations in the "
            + "hierarchy. Pass 'requireLeaf: false' to unlink the organisation along with its entire subtree.";

    // POST /move
    public static final String ERR_NEW_PARENT_NOT_IN_HIERARCHY = "Parent %s is not in the hierarchy";
    public static final String MSG_ALREADY_LINKED_UNDER_PARENT = "Organisation is already linked under this parent";

    // Kafka hierarchy-mutation event streaming (org-hierarchy-edges topic): event types and system-actor labels
    public static final String EVENT_TYPE_LINK = "LINK";
    public static final String EVENT_TYPE_BULK_LINK = "BULK_LINK";
    public static final String EVENT_TYPE_MOVE = "MOVE";
    public static final String EVENT_TYPE_UNLINK = "UNLINK";
    public static final String SYSTEM_USER = "system";
    public static final String PROJECTION_FAILED_PREFIX = "PROJECTION_FAILED: ";

    // parentPathId: the materialized ancestor breadcrumb synced to Cassandra/Elasticsearch after every mutation
    public static final String PATH_SEPARATOR = "_";
    public static final String EMPTY_PARENT_PATH = "";
    public static final String PARENTPATHID = "parentpathid";

    // admin parent-path repair/sync APIs
    public static final String API_ADMIN_PARENT_PATH = "api.org.hierarchy.admin.parentpath";
    public static final String API_ADMIN_PARENT_PATH_SYNC = "api.org.hierarchy.admin.parentpath.sync";

    // bounded executor bean names
    public static final String PARENT_PATH_EXECUTOR = "parentPathExecutor";

    // org_hierarchy_level: hierarchy level customization/retrieval (PATCH & GET /org/v2/hierarchy/levels/{rootOrgId})
    public static final String API_LEVELS_UPDATE = "api.org.hierarchy.levels.update";
    public static final String API_LEVELS_READ = "api.org.hierarchy.levels.read";
    public static final String MSG_LEVELS_UPDATED = "Successfully updated hierarchy level definitions";
    // a fixed business rule (also enforced by org_hierarchy_level's own CHECK constraint), never an environment setting
    public static final int MAX_ALLOWED_LEVEL = 10;

    // Sunbird org-update API request body field names
    public static final String ORGANIZATION_ID = "orgId";
    public static final String PARENT_PATH_ID_KEY = "parentPathId";

    // outbound HTTP calls to external Sunbird APIs (via the shared org.igot.common OutboundRequestHandlerServiceImpl)
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String X_AUTH_USER_HEADER = "x-authenticated-user-token";
    public static final String RESPONSE_CODE_KEY = "responseCode";
    public static final String RESPONSE_CODE_OK = "OK";

    private Constants() {
        throw new IllegalStateException("Utility class");
    }
}
