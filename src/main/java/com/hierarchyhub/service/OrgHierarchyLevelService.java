package com.hierarchyhub.service;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.HierarchyLevelItem;
import com.hierarchyhub.dto.OrgTreeNode;
import com.hierarchyhub.dto.UpdateHierarchyLevelsRequestBody;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;

import java.util.List;
import java.util.Map;

public interface OrgHierarchyLevelService {

    /**
     * Provisions the default {@code L1}..{@code L10} rows for {@code rootOrgId} if none exist yet - a fast no-op
     * once already provisioned. No caller to authorize: called internally by {@code OrgHierarchyServiceImpl} while
     * linking under a Ministry/State root, never directly from a controller.
     */
    void ensureDefaultLevelsProvisioned(String rootOrgId, String userId);

    /** Authorizes the caller, customizes {@code rootOrgId}'s level names/descriptions, and returns the versioned API envelope. */
    ApiResponse updateHierarchyLevels(String rootOrgId, ApiRequest<UpdateHierarchyLevelsRequestBody> request, String accessToken);

    /** Authorizes the caller, reads {@code rootOrgId}'s configured levels, and returns the versioned API envelope. */
    ApiResponse readHierarchyLevels(String rootOrgId, String accessToken);

    /**
     * Every configured level for {@code rootOrgId}, ordered {@code 1..10} - the dynamic level-name lookup
     * {@code OrgHierarchyServiceImpl}'s own {@code /read} and {@code /complete} reads rely on. No caller to
     * authorize: an internal lookup, not an API surface of its own.
     */
    List<HierarchyLevelRecord> findLevels(String rootOrgId);

    /** {@code level -> HierarchyLevelRecord} for {@code rootOrgId}, for both per-node {@code levelName} lookups and the {@code levels} blueprint. */
    Map<Integer, HierarchyLevelRecord> levelMapFor(String rootOrgId);

    /** Falls back to {@code "L" + level} for a level the root hasn't customized (or has no rows for at all). */
    String levelNameOf(Map<Integer, HierarchyLevelRecord> levels, int level);

    /** The {@code startLevel..10} blueprint - every level the caller hasn't customized still reports its {@code "L" + level} fallback name. */
    List<HierarchyLevelItem> levelsBlueprint(Map<Integer, HierarchyLevelRecord> levels, int startLevel);

    /** The deepest level reached anywhere in {@code node}'s own subtree. */
    int maxLevelIn(OrgTreeNode node);
}
