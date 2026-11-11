package com.hierarchyhub.service.impl;

import com.hierarchyhub.config.OrgHierarchyTransactionConfig;
import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.BatchOperationResponse;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyLevelItem;
import com.hierarchyhub.dto.HierarchyLevelsReadResponse;
import com.hierarchyhub.dto.OrgTreeNode;
import com.hierarchyhub.dto.UpdateHierarchyLevelsRequestBody;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.repository.OrgHierarchyLevelRepository;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.util.AppUtil;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.validation.OrgHierarchyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns {@code org_hierarchy_level}: auto-provisioning the default {@code L1}..{@code L10} rows for a new Ministry/State
 * root, and the customize/read levels APIs. Kept separate from {@code OrgHierarchyServiceImpl} the same way
 * {@code OrgUpdateService} was - a distinct concern (level metadata) with its own table, never locked or contended by
 * tree-structural writes (`/link`, `/move`, `/unlink`).
 */
@Service
public class OrgHierarchyLevelServiceImpl implements OrgHierarchyLevelService {

    private static final Logger log = LoggerFactory.getLogger(OrgHierarchyLevelServiceImpl.class);

    private final OrgHierarchyLevelRepository orgHierarchyLevelRepository;
    private final OrgHierarchyRepository orgHierarchyRepository;
    private final OrgHierarchyValidator orgHierarchyValidator;
    private final AuthUtils authUtils;

    public OrgHierarchyLevelServiceImpl(OrgHierarchyLevelRepository orgHierarchyLevelRepository, OrgHierarchyRepository orgHierarchyRepository,
                                         OrgHierarchyValidator orgHierarchyValidator, AuthUtils authUtils) {
        this.orgHierarchyLevelRepository = orgHierarchyLevelRepository;
        this.orgHierarchyRepository = orgHierarchyRepository;
        this.orgHierarchyValidator = orgHierarchyValidator;
        this.authUtils = authUtils;
    }

    @Override
    @Transactional(OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER)
    public void ensureDefaultLevelsProvisioned(String rootOrgId, String userId) {
        if (orgHierarchyLevelRepository.hasLevels(rootOrgId)) {
            return;
        }
        orgHierarchyLevelRepository.insertDefaultLevels(rootOrgId, userId);
        log.info("ensureDefaultLevelsProvisioned: Created default {} hierarchy levels for rootOrgId={}", Constants.MAX_ALLOWED_LEVEL, rootOrgId);
    }

    @Override
    @Transactional(OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER)
    public ApiResponse updateHierarchyLevels(String rootOrgId, ApiRequest<UpdateHierarchyLevelsRequestBody> request, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        requireRootOrgExists(rootOrgId);

        // every row must exist before an update can touch it - a root created before this feature shipped may have none yet
        ensureDefaultLevelsProvisioned(rootOrgId, userId);

        List<HierarchyLevelItem> items = request.getRequest().getLevels();
        orgHierarchyLevelRepository.batchUpdateLevels(rootOrgId, items, userId);
        log.info("updateHierarchyLevels: Updated {} levels for rootOrgId={} by userId={}", items.size(), rootOrgId, userId);

        return AppUtil.versionedResponse(Constants.API_LEVELS_UPDATE, new BatchOperationResponse(Constants.MSG_LEVELS_UPDATED));
    }

    @Override
    @Transactional(value = OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER, readOnly = true)
    public ApiResponse readHierarchyLevels(String rootOrgId, String accessToken) {
        authUtils.validateCallerAuthenticationAndFetchUserId(accessToken);
        requireRootOrgExists(rootOrgId);

        List<HierarchyLevelItem> levels = toDtos(findLevels(rootOrgId));
        HierarchyLevelsReadResponse result = new HierarchyLevelsReadResponse(rootOrgId, Constants.MAX_ALLOWED_LEVEL, levels);
        return AppUtil.versionedResponse(Constants.API_LEVELS_READ, result);
    }

    @Override
    public List<HierarchyLevelRecord> findLevels(String rootOrgId) {
        return orgHierarchyLevelRepository.findLevels(rootOrgId);
    }

    @Override
    public Map<Integer, HierarchyLevelRecord> levelMapFor(String rootOrgId) {
        Map<Integer, HierarchyLevelRecord> levels = new LinkedHashMap<>();
        for (HierarchyLevelRecord level : findLevels(rootOrgId)) {
            levels.put(level.level(), level);
        }
        return levels;
    }

    @Override
    public String levelNameOf(Map<Integer, HierarchyLevelRecord> levels, int level) {
        HierarchyLevelRecord record = levels.get(level);
        return record != null ? record.name() : "L" + level;
    }

    @Override
    public List<HierarchyLevelItem> levelsBlueprint(Map<Integer, HierarchyLevelRecord> levels, int startLevel) {
        List<HierarchyLevelItem> blueprint = new ArrayList<>();
        for (int level = startLevel; level <= Constants.MAX_ALLOWED_LEVEL; level++) {
            HierarchyLevelRecord record = levels.get(level);
            blueprint.add(record != null
                    ? new HierarchyLevelItem(level, record.name(), record.description())
                    : new HierarchyLevelItem(level, "L" + level, null));
        }
        return blueprint;
    }

    @Override
    public int maxLevelIn(OrgTreeNode node) {
        int max = node.getLevel();
        for (OrgTreeNode child : node.getAssociations()) {
            max = Math.max(max, maxLevelIn(child));
        }
        return max;
    }

    private void requireRootOrgExists(String rootOrgId) {
        orgHierarchyValidator.requireExisting(orgHierarchyRepository.findById(rootOrgId).isPresent(),
                "Organisation " + rootOrgId + " is not in the hierarchy");
    }

    private static List<HierarchyLevelItem> toDtos(List<HierarchyLevelRecord> levels) {
        List<HierarchyLevelItem> dtos = new ArrayList<>(levels.size());
        for (HierarchyLevelRecord level : levels) {
            dtos.add(new HierarchyLevelItem(level.level(), level.name(), level.description()));
        }
        return dtos;
    }
}
