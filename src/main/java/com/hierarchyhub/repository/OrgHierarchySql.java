package com.hierarchyhub.repository;

/** Every SQL statement {@link OrgHierarchyRepository} runs against {@code org_hierarchy}, kept in one place for review. */
final class OrgHierarchySql {

    private OrgHierarchySql() {
    }

    static final String SELECT_COLUMNS =
            "id, name, parent_id, idle, child_count, created_at, created_by, updated_at, updated_by";

    static final String FIND_NODE =
            "SELECT " + SELECT_COLUMNS + " FROM org_hierarchy WHERE id = :id";

    static final String FIND_NODES =
            "SELECT " + SELECT_COLUMNS + " FROM org_hierarchy WHERE id IN (:ids)";

    /** Locks the given rows in the caller-supplied (sorted) order, so concurrent writes on the same rows queue instead of deadlocking. */
    static final String LOCK_FOR_UPDATE =
            "SELECT id, parent_id FROM org_hierarchy WHERE id IN (:ids) ORDER BY id FOR UPDATE";

    /** Creates a new child under {@code parent}; a no-op (returns no row) if the id already exists. */
    static final String CREATE_MAPPED =
            "INSERT INTO org_hierarchy (id, name, parent_id, idle, child_count, created_by, updated_by) " +
            "VALUES (:id, :name, :parent, false, 0, :by, :by) ON CONFLICT (id) DO NOTHING RETURNING id";

    static final String SET_PARENT =
            "UPDATE org_hierarchy SET parent_id = :parent, idle = false, updated_by = :by, updated_at = now() WHERE id = :id";

    static final String UNLINK =
            "UPDATE org_hierarchy SET parent_id = NULL, idle = true, updated_by = :by, updated_at = now() WHERE id = :id";

    /**
     * Unlinks every row in {@code :ids} (a whole subtree, root included) in one statement: each becomes idle, with
     * no parent - also {@code child_count = 0} on every one of them, since every row that was ever one of their
     * children is itself in {@code :ids} and just had its own {@code parent_id} cleared the same way.
     */
    static final String CLEAR_SUBTREE =
            "UPDATE org_hierarchy SET parent_id = NULL, idle = true, child_count = 0, updated_by = :by, updated_at = now() " +
            "WHERE id IN (:ids)";

    static final String COUNT_UP =
            "UPDATE org_hierarchy SET child_count = child_count + :n WHERE id = :id";

    static final String COUNT_DOWN =
            "UPDATE org_hierarchy SET child_count = GREATEST(child_count - 1, 0) WHERE id = :id";

    static final String HAS_CHILDREN =
            "SELECT EXISTS (SELECT 1 FROM org_hierarchy WHERE parent_id = :id)";

    /** The chain from {@code id} up to its root (itself first, the L0 last), bounded by {@code maxDepth} hops. */
    static final String CHAIN_UP =
            "WITH RECURSIVE chain(id, parent_id, depth) AS ( " +
            "  SELECT id, parent_id, 1 FROM org_hierarchy WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT o.id, o.parent_id, c.depth + 1 FROM org_hierarchy o JOIN chain c ON o.id = c.parent_id " +
            "  WHERE c.depth < :maxDepth " +
            ") SELECT id FROM chain";

    /**
     * Every descendant of {@code :orgId} down to {@code :depthLimit} hops (1 = direct children only - the same
     * result {@code /read} always returned before search/depth were added), optionally narrowed to rows whose id
     * exactly equals {@code :queryExact} or whose name contains {@code :queryPattern} (a {@code LIKE} pattern already
     * wrapped in {@code %...%} and escaped - see {@link com.hierarchyhub.util.AppUtil#likeContainsPattern}); both are
     * {@code NULL} when no {@code query} was given, which matches every row. First page, alphabetical by name then id.
     */
    static final String DESCENDANTS_FIRST_PAGE =
            "WITH RECURSIVE sub AS ( " +
            "  SELECT " + SELECT_COLUMNS + ", 1 AS depth FROM org_hierarchy WHERE parent_id = :orgId " +
            "  UNION ALL " +
            "  SELECT o.id, o.name, o.parent_id, o.idle, o.child_count, o.created_at, o.created_by, o.updated_at, o.updated_by, s.depth + 1 " +
            "  FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :depthLimit " +
            ") SELECT " + SELECT_COLUMNS + ", depth FROM sub " +
            "WHERE (:queryExact IS NULL OR id = :queryExact OR lower(name) LIKE lower(:queryPattern) ESCAPE '\\') " +
            "ORDER BY lower(name), id LIMIT :limit";

    /** Same as {@link #DESCENDANTS_FIRST_PAGE}, resuming strictly after the given (name, id) cursor. */
    static final String DESCENDANTS_NEXT_PAGE =
            "WITH RECURSIVE sub AS ( " +
            "  SELECT " + SELECT_COLUMNS + ", 1 AS depth FROM org_hierarchy WHERE parent_id = :orgId " +
            "  UNION ALL " +
            "  SELECT o.id, o.name, o.parent_id, o.idle, o.child_count, o.created_at, o.created_by, o.updated_at, o.updated_by, s.depth + 1 " +
            "  FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :depthLimit " +
            ") SELECT " + SELECT_COLUMNS + ", depth FROM sub " +
            "WHERE (:queryExact IS NULL OR id = :queryExact OR lower(name) LIKE lower(:queryPattern) ESCAPE '\\') " +
            "AND (lower(name), id) > (lower(:cursorName), :cursorId) " +
            "ORDER BY lower(name), id LIMIT :limit";

    /**
     * Same filter/depth as {@link #DESCENDANTS_FIRST_PAGE}, but classic {@code LIMIT}/{@code OFFSET} paging instead
     * of a cursor - simpler for a caller that wants plain page numbers, at the cost of {@code OFFSET} getting slower
     * the further in the result set it skips, and possibly skipping/repeating a row if the hierarchy changes between
     * calls (a keyset cursor, {@link #DESCENDANTS_NEXT_PAGE}, doesn't have either problem - prefer it at real scale).
     */
    static final String DESCENDANTS_PAGE_BY_OFFSET =
            "WITH RECURSIVE sub AS ( " +
            "  SELECT " + SELECT_COLUMNS + ", 1 AS depth FROM org_hierarchy WHERE parent_id = :orgId " +
            "  UNION ALL " +
            "  SELECT o.id, o.name, o.parent_id, o.idle, o.child_count, o.created_at, o.created_by, o.updated_at, o.updated_by, s.depth + 1 " +
            "  FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :depthLimit " +
            ") SELECT " + SELECT_COLUMNS + ", depth FROM sub " +
            "WHERE (:queryExact IS NULL OR id = :queryExact OR lower(name) LIKE lower(:queryPattern) ESCAPE '\\') " +
            "ORDER BY lower(name), id LIMIT :limit OFFSET :offset";

    /** Count of every descendant of {@code id} (not including {@code id} itself), bounded by {@code maxDepth} hops. */
    static final String COUNT_DESCENDANTS =
            "WITH RECURSIVE sub(id, depth) AS ( " +
            "  SELECT id, 1 FROM org_hierarchy WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT o.id, s.depth + 1 FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :maxDepth " +
            ") SELECT count(*) - 1 FROM sub";

    /**
     * Just the ids of the subtree rooted at {@code id} (itself included), bounded by {@code maxDepth} hops - no
     * other column is read, so unlinking a 500+ row branch doesn't instantiate 500+ full {@code OrgHierarchyDto}
     * objects on heap just to throw away everything except their ids.
     */
    static final String DESCENDANT_SUBTREE_IDS =
            "WITH RECURSIVE sub(id, depth) AS ( " +
            "  SELECT id, 1 FROM org_hierarchy WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT o.id, s.depth + 1 FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :maxDepth " +
            ") SELECT id FROM sub";

    /**
     * Every row in the subtree rooted at {@code id} (itself included), bounded by {@code maxDepth} hops, each paired
     * with its own relative path (the ids from {@code id} itself down to that row, inclusive) accumulated in a
     * Postgres array - the parentPathId sync engine prepends this to the ancestor chain above {@code id} instead of
     * re-walking from each descendant's own row.
     */
    static final String SUBTREE_WITH_PATHS =
            "WITH RECURSIVE sub AS ( " +
            "  SELECT " + SELECT_COLUMNS + ", ARRAY[id]::text[] AS path_ids, 0 AS depth " +
            "  FROM org_hierarchy WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT o.id, o.name, o.parent_id, o.idle, o.child_count, o.created_at, o.created_by, o.updated_at, o.updated_by, " +
            "         s.path_ids || o.id::text, s.depth + 1 " +
            "  FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :maxDepth " +
            ") SELECT " + SELECT_COLUMNS + ", depth, path_ids FROM sub ORDER BY depth ASC";

    /** Every row in the subtree rooted at {@code id} (itself included), bounded by {@code maxDepth} hops. */
    static final String SUBTREE =
            "WITH RECURSIVE sub AS ( " +
            "  SELECT " + SELECT_COLUMNS + ", 1 AS depth FROM org_hierarchy WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT o.id, o.name, o.parent_id, o.idle, o.child_count, o.created_at, o.created_by, o.updated_at, o.updated_by, s.depth + 1 " +
            "  FROM org_hierarchy o JOIN sub s ON o.parent_id = s.id " +
            "  WHERE s.depth < :maxDepth " +
            ") SELECT " + SELECT_COLUMNS + " FROM sub";
}
