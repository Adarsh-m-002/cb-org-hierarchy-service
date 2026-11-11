package com.hierarchyhub.repository;

/** Every SQL statement {@link OrgHierarchyLevelRepository} runs against {@code org_hierarchy_level}, kept in one place for review. */
final class OrgHierarchyLevelSql {

    private OrgHierarchyLevelSql() {
    }

    static final String HAS_LEVELS =
            "SELECT EXISTS (SELECT 1 FROM org_hierarchy_level WHERE root_org_id = :rootOrgId)";

    /** One row of the default 10-level seed; {@code ON CONFLICT DO NOTHING} so a racing concurrent call is a safe no-op. */
    static final String INSERT_LEVEL =
            "INSERT INTO org_hierarchy_level (root_org_id, level, name, description, created_by, updated_by) " +
            "VALUES (:rootOrgId, :level, :name, :description, :by, :by) " +
            "ON CONFLICT (root_org_id, level) DO NOTHING";

    static final String UPDATE_LEVEL =
            "UPDATE org_hierarchy_level SET name = :name, description = :description, updated_by = :by, updated_at = now() " +
            "WHERE root_org_id = :rootOrgId AND level = :level";

    static final String FIND_LEVELS =
            "SELECT level, name, description FROM org_hierarchy_level WHERE root_org_id = :rootOrgId ORDER BY level ASC";
}
