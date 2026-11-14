package com.hierarchyhub.repository;

import com.hierarchyhub.dto.Constants;

final class OrgHierarchyLevelSql {

    private OrgHierarchyLevelSql() {
    }

    private static final String TABLE_NAME = Constants.ORG_HIERARCHY_LEVEL_TABLE;

    static final String HAS_LEVELS = "SELECT EXISTS (SELECT 1 FROM " + TABLE_NAME + " WHERE id = :id)";

    static final String INSERT_DEFAULT_LEVELS =
            "INSERT INTO " + TABLE_NAME + " (id, levels, created_by, updated_by) " +
            "VALUES (:id, CAST(:levels AS jsonb), :by, :by) " +
            "ON CONFLICT (id) DO NOTHING";

    static final String UPSERT_LEVELS =
            "INSERT INTO " + TABLE_NAME + " (id, levels, created_by, updated_by) " +
            "VALUES (:id, CAST(:levels AS jsonb), :by, :by) " +
            "ON CONFLICT (id) DO UPDATE SET levels = EXCLUDED.levels, updated_by = EXCLUDED.updated_by, updated_at = now()";

    static final String FIND_LEVELS = "SELECT levels FROM " + TABLE_NAME + " WHERE id = :id";
}
