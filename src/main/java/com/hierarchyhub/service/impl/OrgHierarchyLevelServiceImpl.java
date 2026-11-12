package com.hierarchyhub.service.impl;

import com.hierarchyhub.config.OrgHierarchyTransactionConfig;
import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.BatchOperationResponse;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyLevelItem;
import com.hierarchyhub.dto.HierarchyLevelsReadResponse;
import com.hierarchyhub.dto.OrgTreeNode;
import com.hierarchyhub.dto.UpdateHierarchyLevelsRequestBody;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.repository.OrgHierarchyLevelRepository;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.util.AppUtil;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CassandraUtils;
import com.hierarchyhub.validation.OrgHierarchyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Service
public class OrgHierarchyLevelServiceImpl implements OrgHierarchyLevelService {

    private static final Logger log = LoggerFactory.getLogger(OrgHierarchyLevelServiceImpl.class);

    private final OrgHierarchyLevelRepository orgHierarchyLevelRepository;
    private final OrgHierarchyRepository orgHierarchyRepository;
    private final OrgHierarchyValidator orgHierarchyValidator;
    private final CassandraUtils cassandraUtils;
    private final AuthUtils authUtils;

    public OrgHierarchyLevelServiceImpl(OrgHierarchyLevelRepository orgHierarchyLevelRepository, OrgHierarchyRepository orgHierarchyRepository,
                                         OrgHierarchyValidator orgHierarchyValidator, CassandraUtils cassandraUtils, AuthUtils authUtils) {
        this.orgHierarchyLevelRepository = orgHierarchyLevelRepository;
        this.orgHierarchyRepository = orgHierarchyRepository;
        this.orgHierarchyValidator = orgHierarchyValidator;
        this.cassandraUtils = cassandraUtils;
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
        requireActiveL0MinistryOrStateRoot(rootOrgId);

        List<HierarchyLevelItem> normalizedLevels = validateAndNormalizeLevels(request.getRequest().getLevels());
        orgHierarchyLevelRepository.upsertLevels(rootOrgId, normalizedLevels, userId);
        log.info("updateHierarchyLevels: Saved {} levels for rootOrgId={} by userId={}", normalizedLevels.size(), rootOrgId, userId);

        return AppUtil.versionedResponse(Constants.API_LEVELS_UPDATE, new BatchOperationResponse(Constants.MSG_LEVELS_UPDATED));
    }

    @Override
    @Transactional(value = OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER, readOnly = true)
    public ApiResponse readHierarchyLevels(String rootOrgId, String accessToken) {
        authUtils.validateCallerAuthenticationAndFetchUserId(accessToken);
        requireActiveL0MinistryOrStateRoot(rootOrgId);

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
        return record != null ? record.name() : null;
    }

    @Override
    public String levelNameOf(Map<Integer, HierarchyLevelRecord> levels, int level, boolean idle) {
        if (idle) {
            return null;
        }
        if (level == Constants.ROOT_LEVEL_ZERO) {
            return Constants.ROOT_LEVEL_ZERO_NAME;
        }
        return levelNameOf(levels, level);
    }

    @Override
    public List<HierarchyLevelItem> levelsBlueprint(Map<Integer, HierarchyLevelRecord> levels, int startLevel) {
        List<HierarchyLevelItem> blueprint = new ArrayList<>();
        for (HierarchyLevelRecord record : levels.values()) {
            if (record.level() >= startLevel) {
                blueprint.add(new HierarchyLevelItem(record.level(), record.name(), record.description()));
            }
        }
        blueprint.sort(Comparator.comparingInt(HierarchyLevelItem::getLevel));
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

    /** The two levels-config gates shared by GET and PATCH /levels/{rootOrgId}: must exist, be an active L0, and be a Ministry/State per Cassandra. */
    private void requireActiveL0MinistryOrStateRoot(String rootOrgId) {
        Optional<OrgHierarchyDto> org = orgHierarchyRepository.findById(rootOrgId);
        orgHierarchyValidator.requireExisting(org.isPresent(), "Organisation " + rootOrgId + " is not in the hierarchy");
        orgHierarchyValidator.requireActiveRoot(org.get());

        OrganisationRecord orgRecord = cassandraUtils.fetchBatchOrganisationsFromTable(Collections.singletonList(rootOrgId)).get(rootOrgId);
        orgHierarchyValidator.requireMinistryOrStateOrganisationType(orgRecord);
    }

    /** Sorts by level and rejects a duplicate level number outright - a whole-document replace can't silently keep "whichever one came last" from the caller's own payload. */
    private static List<HierarchyLevelItem> validateAndNormalizeLevels(List<HierarchyLevelItem> items) {
        List<HierarchyLevelItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(HierarchyLevelItem::getLevel));
        Set<Integer> seenLevels = new HashSet<>();
        for (HierarchyLevelItem item : sorted) {
            if (!seenLevels.add(item.getLevel())) {
                throw new ServiceException(ErrorCode.INVALID_REQUEST, "Duplicate level numbers are not allowed");
            }
        }
        return sorted;
    }

    private static List<HierarchyLevelItem> toDtos(List<HierarchyLevelRecord> levels) {
        List<HierarchyLevelItem> dtos = new ArrayList<>(levels.size());
        for (HierarchyLevelRecord level : levels) {
            dtos.add(new HierarchyLevelItem(level.level(), level.name(), level.description()));
        }
        return dtos;
    }
}
