package com.hierarchyhub.repository;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyLevelItem;
import com.hierarchyhub.model.HierarchyLevelRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Pure data access for {@code org_hierarchy_level} - no business rule (eligibility, defaults) lives here; see {@code OrgHierarchyLevelService}. */
@Repository
public class OrgHierarchyLevelRepository {

    /** The default 10-level seed's description words, {@code L}{level} as the name - see {@link OrgHierarchyLevelSql#INSERT_LEVEL}. */
    private static final String[] DEFAULT_LEVEL_DESCRIPTIONS = {
            "LevelOne", "LevelTwo", "LevelThree", "LevelFour", "LevelFive",
            "LevelSix", "LevelSeven", "LevelEight", "LevelNine", "LevelTen"};

    private final NamedParameterJdbcTemplate jdbc;

    public OrgHierarchyLevelRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasLevels(String rootOrgId) {
        Boolean exists = jdbc.queryForObject(OrgHierarchyLevelSql.HAS_LEVELS, params(rootOrgId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Batch-inserts the default {@code L1}..{@code L10} rows for {@code rootOrgId}; a racing concurrent call is a safe no-op per row. */
    public void insertDefaultLevels(String rootOrgId, String userId) {
        SqlParameterSource[] batchParams = new SqlParameterSource[Constants.MAX_ALLOWED_LEVEL];
        for (int level = 1; level <= Constants.MAX_ALLOWED_LEVEL; level++) {
            batchParams[level - 1] = params(rootOrgId)
                    .addValue(Constants.LEVEL, level)
                    .addValue(Constants.NAME, "L" + level)
                    .addValue(Constants.DESCRIPTION, DEFAULT_LEVEL_DESCRIPTIONS[level - 1])
                    .addValue(Constants.PARAM_UPDATED_BY, userId);
        }
        jdbc.batchUpdate(OrgHierarchyLevelSql.INSERT_LEVEL, batchParams);
    }

    public void batchUpdateLevels(String rootOrgId, List<HierarchyLevelItem> items, String userId) {
        SqlParameterSource[] batchParams = new SqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            HierarchyLevelItem item = items.get(i);
            batchParams[i] = params(rootOrgId)
                    .addValue(Constants.LEVEL, item.getLevel())
                    .addValue(Constants.NAME, item.getName())
                    .addValue(Constants.DESCRIPTION, item.getDescription())
                    .addValue(Constants.PARAM_UPDATED_BY, userId);
        }
        jdbc.batchUpdate(OrgHierarchyLevelSql.UPDATE_LEVEL, batchParams);
    }

    /** Every configured level for {@code rootOrgId}, ordered {@code 1..10} (empty if none have been provisioned yet). */
    public List<HierarchyLevelRecord> findLevels(String rootOrgId) {
        return jdbc.query(OrgHierarchyLevelSql.FIND_LEVELS, params(rootOrgId), ROW_MAPPER);
    }

    private static MapSqlParameterSource params(String rootOrgId) {
        return new MapSqlParameterSource("rootOrgId", rootOrgId);
    }

    private static final RowMapper<HierarchyLevelRecord> ROW_MAPPER = (rs, rowNum) ->
            new HierarchyLevelRecord(rs.getInt(Constants.LEVEL), rs.getString(Constants.NAME), rs.getString(Constants.DESCRIPTION));
}
