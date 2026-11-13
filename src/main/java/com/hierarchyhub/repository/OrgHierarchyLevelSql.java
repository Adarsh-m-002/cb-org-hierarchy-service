package com.hierarchyhub.repository;

final class OrgHierarchyLevelSql {

    private OrgHierarchyLevelSql() {
    }

    static final String HAS_LEVELS =
            "SELECT EXISTS (SELECT 1 FROM org_hierarchy_level WHERE id = :id)";

    static final String INSERT_DEFAULT_LEVELS =
            "INSERT INTO org_hierarchy_level (id, levels, created_by, updated_by) " +
            "VALUES (:id, CAST(:levels AS jsonb), :by, :by) " +
            "ON CONFLICT (id) DO NOTHING";

    static final String UPSERT_LEVELS =
            "INSERT INTO org_hierarchy_level (id, levels, created_by, updated_by) " +
            "VALUES (:id, CAST(:levels AS jsonb), :by, :by) " +
            "ON CONFLICT (id) DO UPDATE SET levels = EXCLUDED.levels, updated_by = EXCLUDED.updated_by, updated_at = now()";

    static final String FIND_LEVELS =
            "SELECT levels FROM org_hierarchy_level WHERE id = :id";
}
