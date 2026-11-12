package com.hierarchyhub.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyLevelItem;
import com.hierarchyhub.model.HierarchyLevelRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Repository
public class OrgHierarchyLevelRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The default 10-level seed's description words, {@code L}{level} as the name - see {@link #insertDefaultLevels}. */
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

    /** Inserts the default {@code L1}..{@code L10} JSONB array for {@code rootOrgId} as one row; a racing concurrent call is a safe no-op. */
    public void insertDefaultLevels(String rootOrgId, String userId) {
        List<HierarchyLevelRecord> defaults = new ArrayList<>(Constants.MAX_ALLOWED_LEVEL);
        for (int level = 1; level <= Constants.MAX_ALLOWED_LEVEL; level++) {
            defaults.add(new HierarchyLevelRecord(level, "L" + level, DEFAULT_LEVEL_DESCRIPTIONS[level - 1]));
        }
        jdbc.update(OrgHierarchyLevelSql.INSERT_DEFAULT_LEVELS, writeParams(rootOrgId, defaults, userId));
    }

    /** Replaces the entire stored {@code levels} array for {@code rootOrgId} with exactly {@code items} - a whole-document upsert, never a per-level merge. */
    public void upsertLevels(String rootOrgId, List<HierarchyLevelItem> items, String userId) {
        jdbc.update(OrgHierarchyLevelSql.UPSERT_LEVELS, writeParams(rootOrgId, recordsOf(items), userId));
    }

    /** Every level stored for {@code rootOrgId}, ordered {@code level ASC} - empty if no row exists yet; never fabricated. */
    public List<HierarchyLevelRecord> findLevels(String rootOrgId) {
        List<String> rows = jdbc.queryForList(OrgHierarchyLevelSql.FIND_LEVELS, params(rootOrgId), String.class);
        return rows.isEmpty() ? Collections.emptyList() : deserialize(rows.get(0));
    }

    private static MapSqlParameterSource params(String rootOrgId) {
        return new MapSqlParameterSource("id", rootOrgId);
    }

    private static MapSqlParameterSource writeParams(String rootOrgId, List<HierarchyLevelRecord> records, String userId) {
        List<HierarchyLevelRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingInt(HierarchyLevelRecord::level));
        return params(rootOrgId).addValue("levels", serialize(sorted)).addValue(Constants.PARAM_UPDATED_BY, userId);
    }

    private static List<HierarchyLevelRecord> recordsOf(List<HierarchyLevelItem> items) {
        List<HierarchyLevelRecord> records = new ArrayList<>(items.size());
        for (HierarchyLevelItem item : items) {
            records.add(new HierarchyLevelRecord(item.getLevel(), item.getName(), item.getDescription()));
        }
        return records;
    }

    private static String serialize(List<HierarchyLevelRecord> records) {
        try {
            return MAPPER.writeValueAsString(records);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize hierarchy levels", e);
        }
    }

    private static List<HierarchyLevelRecord> deserialize(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<HierarchyLevelRecord>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialize stored hierarchy levels", e);
        }
    }
}
