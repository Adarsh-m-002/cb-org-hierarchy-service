package com.hierarchyhub.repository;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrgHierarchyDtoWithDepth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrgHierarchyRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final int maxDepth;

    public OrgHierarchyRepository(NamedParameterJdbcTemplate jdbc,
                                   @Value("${org-hierarchy.max-depth}") int maxDepth) {
        this.jdbc = jdbc;
        this.maxDepth = maxDepth;
    }

    public Optional<OrgHierarchyDto> findById(String id) {
        List<OrgHierarchyDto> rows = jdbc.query(OrgHierarchySql.FIND_NODE, params("id", id), ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The nodes that exist among {@code ids}, keyed by id; an id missing from the map is not in the hierarchy at all. */
    public Map<String, OrgHierarchyDto> findExisting(Collection<String> ids) {
        if (ids.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, OrgHierarchyDto> out = new LinkedHashMap<>();
        for (OrgHierarchyDto node : jdbc.query(OrgHierarchySql.FIND_NODES, idsParam(ids), ROW_MAPPER)) {
            out.put(node.id(), node);
        }
        return out;
    }

    /** The chain of ids from {@code id} up to its root L0 (itself first), at most {@code maxDepth} hops. Empty if {@code id} is unknown. */
    public List<String> chainUp(String id) {
        MapSqlParameterSource p = params("id", id).addValue("maxDepth", maxDepth);
        return jdbc.queryForList(OrgHierarchySql.CHAIN_UP, p, String.class);
    }

    public boolean hasChildren(String id) {
        Boolean has = jdbc.queryForObject(OrgHierarchySql.HAS_CHILDREN, params("id", id), Boolean.class);
        return Boolean.TRUE.equals(has);
    }

    public List<OrgHierarchyDtoWithDepth> findDescendants(String orgId, int depthLimit, String queryExact, String queryPattern,
                                                   String cursorName, String cursorId, int limit) {
        MapSqlParameterSource p = params("orgId", orgId).addValue("depthLimit", depthLimit).addValue("limit", limit)
                .addValue("queryExact", queryExact, Types.VARCHAR).addValue("queryPattern", queryPattern, Types.VARCHAR);
        if (cursorName == null) {
            return jdbc.query(OrgHierarchySql.DESCENDANTS_FIRST_PAGE, p, ROW_MAPPER_WITH_DEPTH);
        }
        p.addValue("cursorName", cursorName).addValue("cursorId", cursorId);
        return jdbc.query(OrgHierarchySql.DESCENDANTS_NEXT_PAGE, p, ROW_MAPPER_WITH_DEPTH);
    }

    /** Same as {@link #findDescendants}, but classic {@code limit}/{@code offset} paging - see {@link OrgHierarchySql#DESCENDANTS_PAGE_BY_OFFSET}. */
    public List<OrgHierarchyDtoWithDepth> findDescendantsByOffset(String orgId, int depthLimit, String queryExact, String queryPattern, int offset, int limit) {
        MapSqlParameterSource p = params("orgId", orgId).addValue("depthLimit", depthLimit).addValue("offset", offset).addValue("limit", limit)
                .addValue("queryExact", queryExact, Types.VARCHAR).addValue("queryPattern", queryPattern, Types.VARCHAR);
        return jdbc.query(OrgHierarchySql.DESCENDANTS_PAGE_BY_OFFSET, p, ROW_MAPPER_WITH_DEPTH);
    }

    public int countDescendants(String id) {
        MapSqlParameterSource p = params("id", id).addValue("maxDepth", maxDepth);
        Integer count = jdbc.queryForObject(OrgHierarchySql.COUNT_DESCENDANTS, p, Integer.class);
        return count == null ? 0 : count;
    }

    /** Every row of the subtree rooted at {@code id} (itself included), at most {@code maxDepth} hops deep. */
    public List<OrgHierarchyDto> findSubtree(String id) {
        MapSqlParameterSource p = params("id", id).addValue("maxDepth", maxDepth);
        return jdbc.query(OrgHierarchySql.SUBTREE, p, ROW_MAPPER);
    }

    /**
     * Just the ids of {@code id}'s own subtree (itself included), at most {@code maxDepth} hops deep - for an
     * unlink-the-whole-branch call, where only the ids are ever needed (to lock and then clear), not a full
     * {@link OrgHierarchyDto} per row.
     */
    public List<String> findDescendantSubtreeOrgIds(String id) {
        MapSqlParameterSource p = params("id", id).addValue("maxDepth", maxDepth);
        return jdbc.queryForList(OrgHierarchySql.DESCENDANT_SUBTREE_IDS, p, String.class);
    }

    /** One row of {@link #streamSubtreeWithPaths}: the node itself, its hop count below {@code id}, and its own relative path (ids from {@code id} down to it, inclusive). */
    public interface PathRowCallback {
        void process(OrgHierarchyDto node, int depth, List<String> pathIds);
    }

    /** Streams every row of {@code id}'s own subtree (itself included), each paired with its relative path - see {@link OrgHierarchySql#SUBTREE_WITH_PATHS}. */
    public void streamSubtreeWithPaths(String id, PathRowCallback callback) {
        MapSqlParameterSource p = params("id", id).addValue("maxDepth", maxDepth);
        jdbc.query(OrgHierarchySql.SUBTREE_WITH_PATHS, p, (RowCallbackHandler) rs -> {
            OrgHierarchyDto node = mapRow(rs, 0);
            int depth = rs.getInt(Constants.DEPTH);
            String[] pathIds = (String[]) rs.getArray("path_ids").getArray();
            callback.process(node, depth, Arrays.asList(pathIds));
        });
    }

    /** Locks the given rows in the caller-supplied order (sort before calling, so two writers never deadlock). Must run inside a transaction. */
    public void lockRows(Collection<String> ids) {
        if (!ids.isEmpty()) {
            jdbc.query(OrgHierarchySql.LOCK_FOR_UPDATE, idsParam(ids), (rs, i) -> rs.getString("id"));
        }
    }

    /** Inserts a new child row under {@code parentId}; empty when the id already exists (another request created it first). */
    public Optional<OrgHierarchyDto> createMapped(String id, String name, String parentId, String userId) {
        List<String> inserted = jdbc.queryForList(OrgHierarchySql.CREATE_MAPPED,
                params("id", id).addValue("name", name).addValue("parent", parentId).addValue(Constants.PARAM_UPDATED_BY, userId), String.class);
        return inserted.isEmpty() ? Optional.empty() : findById(id);
    }

    /** Creates a brand-new hierarchy root (no parent of its own); empty if the id already exists. */
    public Optional<OrgHierarchyDto> createRoot(String id, String name, String userId) {
        List<String> inserted = jdbc.queryForList(OrgHierarchySql.CREATE_MAPPED,
                params("id", id).addValue("name", name).addValue("parent", null, Types.VARCHAR).addValue(Constants.PARAM_UPDATED_BY, userId), String.class);
        return inserted.isEmpty() ? Optional.empty() : findById(id);
    }

    /** Inserts many new children under {@code parentId} in one JDBC batch, instead of one round-trip per child. */
    public void batchInsertMapped(List<String> ids, List<String> names, String parentId, String userId) {
        SqlParameterSource[] batchParams = new SqlParameterSource[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            batchParams[i] = params("id", ids.get(i)).addValue("name", names.get(i)).addValue("parent", parentId).addValue(Constants.PARAM_UPDATED_BY, userId);
        }
        jdbc.batchUpdate(OrgHierarchySql.CREATE_MAPPED, batchParams);
    }

    public void setParent(String id, String parentId, String userId) {
        jdbc.update(OrgHierarchySql.SET_PARENT, params("id", id).addValue("parent", parentId).addValue(Constants.PARAM_UPDATED_BY, userId));
    }

    /** Re-parents many already-mapped children onto {@code parentId} in one JDBC batch. */
    public void batchSetParent(List<String> ids, String parentId, String userId) {
        SqlParameterSource[] batchParams = new SqlParameterSource[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            batchParams[i] = params("id", ids.get(i)).addValue("parent", parentId).addValue(Constants.PARAM_UPDATED_BY, userId);
        }
        jdbc.batchUpdate(OrgHierarchySql.SET_PARENT, batchParams);
    }

    public void unlinkRow(String id, String userId) {
        jdbc.update(OrgHierarchySql.UNLINK, params("id", id).addValue(Constants.PARAM_UPDATED_BY, userId));
    }

    /** Unlinks every row in {@code ids} (a whole subtree, root included) in one statement - see {@link OrgHierarchySql#CLEAR_SUBTREE}. */
    public void clearSubtree(Collection<String> ids, String userId) {
        jdbc.update(OrgHierarchySql.CLEAR_SUBTREE, idsParam(ids).addValue(Constants.PARAM_UPDATED_BY, userId));
    }

    public void incrementChildCount(String parentId) {
        incrementChildCountBy(parentId, 1);
    }

    /** Increments {@code parentId}'s child count by {@code n} in one statement, for a batch attach of {@code n} children at once. */
    public void incrementChildCountBy(String parentId, int n) {
        jdbc.update(OrgHierarchySql.COUNT_UP, params("id", parentId).addValue("n", n));
    }

    public void decrementChildCount(String parentId) {
        jdbc.update(OrgHierarchySql.COUNT_DOWN, params("id", parentId));
    }

    private static MapSqlParameterSource params(String name, Object value) {
        return new MapSqlParameterSource(name, value);
    }

    private static MapSqlParameterSource idsParam(Collection<String> ids) {
        return new MapSqlParameterSource("ids", ids);
    }

    private static final RowMapper<OrgHierarchyDto> ROW_MAPPER = OrgHierarchyRepository::mapRow;
    private static final RowMapper<OrgHierarchyDtoWithDepth> ROW_MAPPER_WITH_DEPTH =
            (rs, rowNum) -> new OrgHierarchyDtoWithDepth(mapRow(rs, rowNum), rs.getInt(Constants.DEPTH));

    private static OrgHierarchyDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp(Constants.CREATED_AT);
        Timestamp updatedAt = rs.getTimestamp(Constants.UPDATED_AT);
        return new OrgHierarchyDto(
                rs.getString(Constants.ID),
                rs.getString(Constants.NAME),
                rs.getString(Constants.PARENT_ID),
                rs.getBoolean(Constants.IDLE),
                rs.getInt(Constants.CHILD_COUNT),
                createdAt == null ? null : createdAt.toInstant(),
                rs.getString(Constants.CREATED_BY),
                updatedAt == null ? null : updatedAt.toInstant(),
                rs.getString(Constants.UPDATED_BY));
    }
}
