package com.hierarchyhub.service.impl;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.config.OrgHierarchyTransactionConfig;
import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.AssociationRef;
import com.hierarchyhub.dto.BatchLinkExecutionResult;
import com.hierarchyhub.dto.BatchOperationResponse;
import com.hierarchyhub.dto.ChildOrgSummary;
import com.hierarchyhub.dto.HierarchyEvent;
import com.hierarchyhub.dto.HierarchyResponse;
import com.hierarchyhub.dto.LinkOrgRequestBody;
import com.hierarchyhub.dto.OrgLinkErrorDetail;
import com.hierarchyhub.dto.MoveOrgRequestBody;
import com.hierarchyhub.dto.MoveOrgResponse;
import com.hierarchyhub.dto.OrgTreeNode;
import com.hierarchyhub.dto.ReadChildrenRequestBody;
import com.hierarchyhub.dto.ReadChildrenResponse;
import com.hierarchyhub.dto.UnlinkOrgRequestBody;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrgHierarchyDtoWithDepth;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.HierarchyEventPublisher;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.service.OrgHierarchyService;
import com.hierarchyhub.util.AppUtil;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CassandraUtils;
import com.hierarchyhub.validation.OrgHierarchyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class OrgHierarchyServiceImpl implements OrgHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(OrgHierarchyServiceImpl.class);

    private final OrgHierarchyRepository orgHierarchyRepository;
    private final OrgHierarchyValidator orgHierarchyValidator;
    private final AuthUtils authUtils;
    private final CassandraUtils cassandraUtils;
    private final HierarchyEventPublisher hierarchyEventPublisher;
    private final OrgHierarchyLevelService orgHierarchyLevelService;
    private final int maxDepth;

    public OrgHierarchyServiceImpl(OrgHierarchyRepository orgHierarchyRepository, OrgHierarchyValidator orgHierarchyValidator,
                                    AuthUtils authUtils, CassandraUtils cassandraUtils, HierarchyEventPublisher hierarchyEventPublisher,
                                    OrgHierarchyLevelService orgHierarchyLevelService,
                                    @Value("${org-hierarchy.max-depth}") int maxDepth) {
        this.orgHierarchyRepository = orgHierarchyRepository;
        this.orgHierarchyValidator = orgHierarchyValidator;
        this.authUtils = authUtils;
        this.cassandraUtils = cassandraUtils;
        this.hierarchyEventPublisher = hierarchyEventPublisher;
        this.orgHierarchyLevelService = orgHierarchyLevelService;
        this.maxDepth = maxDepth;
    }

    @Override
    @Transactional(OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER)
    public ApiResponse linkOrgToHierarchy(ApiRequest<LinkOrgRequestBody> request, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);

        String parentOrgId = request.getRequest().getParentOrgId();
        List<String> childOrganisationIds = extractUniqueChildIdentifiers(request.getRequest().getAssociations());
        log.info("Starting linkOrgToHierarchy: parentOrgId={}, requestedChildCount={}", parentOrgId, childOrganisationIds.size());

        BatchLinkExecutionResult executionResult = linkChildrenUnderParentBatch(parentOrgId, childOrganisationIds, userId);

        return buildLinkApiResponse(childOrganisationIds.size(),
                executionResult.getSucceededChildOrganisationIds().size(), executionResult.getFailureErrors());
    }

    @Override
    @Transactional(OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER)
    public BatchLinkExecutionResult linkChildrenUnderParentBatch(String parentOrgId, List<String> childOrganisationIds, String userId) {
        if (CollectionUtils.isEmpty(childOrganisationIds)) {
            return new BatchLinkExecutionResult(Collections.emptyList(), Collections.emptyList());
        }

        TreeSet<String> rowsToLock = sorted(childOrganisationIds);
        rowsToLock.add(parentOrgId);
        orgHierarchyRepository.lockRows(rowsToLock);

        OrganisationRecord parentOrganisationRecord = validateParentAndCreateHierarchyIfNeeded(parentOrgId, userId);

        Map<String, OrganisationRecord> childOrganisationRecords = cassandraUtils.fetchBatchOrganisationsFromTable(childOrganisationIds);
        Map<String, OrgHierarchyDto> existingHierarchyNodes = orgHierarchyRepository.findExisting(childOrganisationIds);
        Set<String> parentAncestorChain = new HashSet<>(orgHierarchyRepository.chainUp(parentOrgId));

        int capacity = childOrganisationIds.size();
        List<String> newChildIdsToInsert = new ArrayList<>(capacity);
        List<String> newChildNamesToInsert = new ArrayList<>(capacity);
        List<String> existingChildIdsToUpdate = new ArrayList<>(capacity);
        List<String> succeededChildOrganisationIds = new ArrayList<>(capacity);
        List<OrgLinkErrorDetail> failureErrors = new ArrayList<>();

        for (String childId : childOrganisationIds) {
            orgHierarchyValidator.validateAndCategorizeChild(childId, parentOrgId, parentOrganisationRecord,
                    childOrganisationRecords.get(childId), existingHierarchyNodes.get(childId), parentAncestorChain,
                    newChildIdsToInsert, newChildNamesToInsert, existingChildIdsToUpdate, succeededChildOrganisationIds, failureErrors);
        }

        // Persist database updates in batch - at most one insert batch, one update batch, one counter update.
        persistBatchChildLinks(parentOrgId, userId, newChildIdsToInsert, newChildNamesToInsert, existingChildIdsToUpdate);

        logBatchLinkSuccessSummary(parentOrgId, succeededChildOrganisationIds, failureErrors.size());

        List<HierarchyEvent> linkEventsToPublish = new ArrayList<>(succeededChildOrganisationIds.size());
        for (String childId : succeededChildOrganisationIds) {
            String childName = childOrganisationRecords.containsKey(childId) ? childOrganisationRecords.get(childId).name() : childId;
            linkEventsToPublish.add(createLinkEvent(childId, parentOrgId, childName, userId));
        }
        publishAfterCommit(() -> hierarchyEventPublisher.publishBatchAsync(linkEventsToPublish));

        return new BatchLinkExecutionResult(succeededChildOrganisationIds, failureErrors);
    }

    private OrganisationRecord validateParentAndCreateHierarchyIfNeeded(String parentId, String userId) {
        OrganisationRecord parentRecord = cassandraUtils.fetchBatchOrganisationsFromTable(Collections.singletonList(parentId)).get(parentId);
        orgHierarchyValidator.requireActiveParentOrganisation(parentId, parentRecord);
        if (!orgHierarchyRepository.findById(parentId).isPresent()) {
            orgHierarchyValidator.requireMinistryOrStateRoot(parentId, parentRecord);
            orgHierarchyRepository.createRoot(parentId, nameOrId(parentRecord), userId);
            orgHierarchyLevelService.ensureDefaultLevelsProvisioned(parentId, userId);
        } else if (orgHierarchyValidator.isMinistryOrStateRoot(parentRecord)) {
            // safe idempotency: a root created before the levels engine shipped may not have any rows yet
            orgHierarchyLevelService.ensureDefaultLevelsProvisioned(parentId, userId);
        }
        return parentRecord;
    }

    private void persistBatchChildLinks(String parentId, String userId,
                                         List<String> newChildIdsToInsert, List<String> newChildNamesToInsert,
                                         List<String> existingChildIdsToUpdate) {
        if (!CollectionUtils.isEmpty(newChildIdsToInsert)) {
            orgHierarchyRepository.batchInsertMapped(newChildIdsToInsert, newChildNamesToInsert, parentId, userId);
        }
        if (!CollectionUtils.isEmpty(existingChildIdsToUpdate)) {
            orgHierarchyRepository.batchSetParent(existingChildIdsToUpdate, parentId, userId);
        }
        int totalNewlyAttached = newChildIdsToInsert.size() + existingChildIdsToUpdate.size();
        if (totalNewlyAttached > 0) {
            orgHierarchyRepository.incrementChildCountBy(parentId, totalNewlyAttached);
        }
    }

    private void logBatchLinkSuccessSummary(String parentId, List<String> succeededChildIds, int failedCount) {
        log.info("Batch link completed: parentId={}, attachedChildCount={}, succeededChildIds={}, failedCount={}",
                parentId, succeededChildIds.size(), succeededChildIds, failedCount);
    }

    private ApiResponse buildLinkApiResponse(int totalCount, int successCount, List<OrgLinkErrorDetail> failureErrors) {
        if (totalCount == 1 && !CollectionUtils.isEmpty(failureErrors)) {
            OrgLinkErrorDetail failure = failureErrors.get(0);
            return ApiResponse.failure(Constants.API_LINK, HttpStatus.BAD_REQUEST, failure.getErr(), failure.getErrmsg());
        }
        if (successCount == 0 && !CollectionUtils.isEmpty(failureErrors)) {
            ApiResponse response = ApiResponse.failure(Constants.API_LINK, HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_REQUEST.name(), Constants.MSG_ALL_ORGS_FAILED_TO_LINK);
            populateLinkResultDetails(response, Constants.MSG_ALL_ORGS_FAILED_TO_LINK, totalCount, 0, failureErrors.size(), failureErrors);
            return response;
        }
        if (!CollectionUtils.isEmpty(failureErrors)) {
            ApiResponse response = versionedResponse(Constants.API_LINK, null);
            response.getParams().setStatus(Constants.STATUS_PARTIAL_SUCCESS);
            response.getParams().setErrmsg(String.format(Constants.MSG_FAILED_TO_LINK_N_OF_TOTAL, failureErrors.size(), totalCount));
            populateLinkResultDetails(response, Constants.MSG_ORG_PARTIALLY_ADDED, totalCount, successCount, failureErrors.size(), failureErrors);
            return response;
        }
        ApiResponse response = versionedResponse(Constants.API_LINK, null);
        populateLinkResultDetails(response, Constants.MSG_ORG_ADDED_TO_HIERARCHY, totalCount, successCount, 0, null);
        return response;
    }

    private void populateLinkResultDetails(ApiResponse response, String message, int totalCount, int successCount,
                                            int failedCount, List<OrgLinkErrorDetail> failureErrors) {
        response.put(Constants.MESSAGE, message);
        response.put(Constants.KEY_TOTAL_COUNT, totalCount);
        response.put(Constants.KEY_SUCCESS_COUNT, successCount);
        response.put(Constants.KEY_FAILED_COUNT, failedCount);
        if (!CollectionUtils.isEmpty(failureErrors)) {
            response.put(Constants.KEY_ERRORS, failureErrors);
        }
    }

    /** Dedupes while preserving order: a duplicate childOrgId would otherwise be validated/attached twice and double-count. */
    private static List<String> extractUniqueChildIdentifiers(List<AssociationRef> associations) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (AssociationRef ref : associations) {
            ids.add(ref.getChildOrgId());
        }
        return new ArrayList<>(ids);
    }

    @Override
    @Transactional(OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER)
    public ApiResponse moveOrgInHierarchy(ApiRequest<MoveOrgRequestBody> request, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        String orgId = request.getRequest().getOrgId();
        String newParentId = request.getRequest().getNewParentOrgId();
        log.info("Starting moveOrgInHierarchy: orgId={}, newParentId={}", orgId, newParentId);

        orgHierarchyValidator.validateDistinctEntities(orgId, newParentId);
        Map<String, OrganisationRecord> moveOrganisationRecords = cassandraUtils.fetchBatchOrganisationsFromTable(Arrays.asList(orgId, newParentId));
        orgHierarchyValidator.validateMoveEligibilityInTable(orgId, newParentId, moveOrganisationRecords.get(orgId), moveOrganisationRecords.get(newParentId));

        OrgHierarchyDto existingOrgHierarchyDto = orgHierarchyRepository.findById(orgId).orElseThrow(() -> notFound(orgId));
        if (!orgHierarchyValidator.hasParent(existingOrgHierarchyDto)) {
            throw new ServiceException(ErrorCode.NOT_LINKED, "Org " + orgId + " is unlinked: use /link, not /move");
        }
        String oldParentIdBeforeLock = existingOrgHierarchyDto.parentId();

        orgHierarchyRepository.lockRows(sorted(orgId, oldParentIdBeforeLock, newParentId));

        OrgHierarchyDto lockedOrgHierarchyDto = requireUnchangedParent(orgId, oldParentIdBeforeLock);
        orgHierarchyValidator.requireExisting(orgHierarchyRepository.findById(newParentId).isPresent(),
                String.format(Constants.ERR_NEW_PARENT_NOT_IN_HIERARCHY, newParentId));

        if (orgHierarchyValidator.isAlreadyLinkedTo(lockedOrgHierarchyDto, newParentId)) {
            log.info("moveOrgInHierarchy idempotent no-op: orgId={} is already under newParentId={}", orgId, newParentId);
            MoveOrgResponse noOp = new MoveOrgResponse(orgId, lockedOrgHierarchyDto.parentId(), newParentId, false,
                    Constants.MSG_ALREADY_LINKED_UNDER_PARENT); // idempotent no-op, genuinely distinct from a move
            return versionedResponse(Constants.API_MOVE, noOp);
        }
        orgHierarchyValidator.validateCyclePrevention(orgId, newParentId, orgHierarchyRepository.chainUp(newParentId));

        String oldParentId = lockedOrgHierarchyDto.parentId();
        orgHierarchyRepository.setParent(orgId, newParentId, userId);
        orgHierarchyRepository.decrementChildCount(oldParentId);
        orgHierarchyRepository.incrementChildCount(newParentId);
        log.info("moveOrgInHierarchy completed: orgId={}, oldParentId={}, newParentId={}", orgId, oldParentId, newParentId);

        HierarchyEvent moveEvent = createMoveEvent(orgId, newParentId, oldParentId, lockedOrgHierarchyDto.name(), userId);
        publishAfterCommit(() -> hierarchyEventPublisher.publishAsync(moveEvent));

        MoveOrgResponse moved = new MoveOrgResponse(orgId, oldParentId, newParentId, true, Constants.MSG_ORG_MOVED_IN_HIERARCHY);
        return versionedResponse(Constants.API_MOVE, moved);
    }

    @Override
    @Transactional(OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER)
    public ApiResponse unlinkOrgFromHierarchy(ApiRequest<UnlinkOrgRequestBody> request, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        String orgId = request.getRequest().getOrgId();

        // safe default: unlink just orgId (leaf-only) unless the caller explicitly opts into the whole subtree
        Boolean requestedRequireLeaf = request.getRequest().getRequireLeaf();
        boolean requireLeaf = requestedRequireLeaf == null || requestedRequireLeaf;
        log.info("Starting unlinkOrgFromHierarchy: orgId={}, requireLeaf={}", orgId, requireLeaf);

        OrgHierarchyDto existingOrgHierarchyDto = orgHierarchyRepository.findById(orgId).orElseThrow(() -> notFound(orgId));
        if (!orgHierarchyValidator.hasParent(existingOrgHierarchyDto)) {
            throw new ServiceException(ErrorCode.NOT_LINKED, "Org " + orgId + " has no parent");
        }
        String parentIdBeforeLock = existingOrgHierarchyDto.parentId();

        if (requireLeaf) {
            unlinkSingleLeafOrganisation(orgId, parentIdBeforeLock, userId);
        } else {
            unlinkEntireSubtreeBranch(orgId, parentIdBeforeLock, userId);
        }

        return versionedResponse(Constants.API_UNLINK, new BatchOperationResponse(Constants.MSG_ORG_REMOVED_FROM_HIERARCHY));
    }

    private void unlinkSingleLeafOrganisation(String orgId, String parentIdBeforeLock, String userId) {
        orgHierarchyRepository.lockRows(sorted(orgId, parentIdBeforeLock));
        OrgHierarchyDto lockedOrgHierarchyDto = requireUnchangedParent(orgId, parentIdBeforeLock);

        orgHierarchyValidator.requireNoActiveChildren(orgId, orgHierarchyRepository.hasChildren(orgId));

        orgHierarchyRepository.unlinkRow(orgId, userId);
        orgHierarchyRepository.decrementChildCount(lockedOrgHierarchyDto.parentId());
        log.info("unlinkSingleLeafOrganisation completed: orgId={}, parentId={}", orgId, lockedOrgHierarchyDto.parentId());

        HierarchyEvent unlinkEvent = createUnlinkEvent(orgId, lockedOrgHierarchyDto.parentId(), lockedOrgHierarchyDto.name(), userId);
        publishAfterCommit(() -> hierarchyEventPublisher.publishAsync(unlinkEvent));
    }

    private void unlinkEntireSubtreeBranch(String orgId, String parentIdBeforeLock, String userId) {
        List<String> descendantSubtreeOrgIds = orgHierarchyRepository.findDescendantSubtreeOrgIds(orgId); // unlocked read; re-verified below once locked

        TreeSet<String> rowsToLock = sorted(descendantSubtreeOrgIds);
        rowsToLock.add(parentIdBeforeLock);
        orgHierarchyRepository.lockRows(rowsToLock);
        OrgHierarchyDto lockedOrgHierarchyDto = requireUnchangedParent(orgId, parentIdBeforeLock);

        orgHierarchyRepository.clearSubtree(descendantSubtreeOrgIds, userId);
        orgHierarchyRepository.decrementChildCount(lockedOrgHierarchyDto.parentId());
        log.info("unlinkEntireSubtreeBranch completed: orgId={}, parentId={}, unlinkedSubtreeOrgCount={}",
                orgId, lockedOrgHierarchyDto.parentId(), descendantSubtreeOrgIds.size());

        // Every detached org (orgId and its whole former branch) gets its own event - the branch's own internal
        // parent/child pointers are gone now (all cleared to null in one statement), so each is reported unparented.
        List<HierarchyEvent> subtreeUnlinkEvents = new ArrayList<>(descendantSubtreeOrgIds.size());
        for (String detachedId : descendantSubtreeOrgIds) {
            subtreeUnlinkEvents.add(createUnlinkEvent(detachedId, null, detachedId, userId));
        }
        publishAfterCommit(() -> hierarchyEventPublisher.publishBatchAsync(subtreeUnlinkEvents));
    }

    @Override
    @Transactional(value = OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER, readOnly = true)
    public ApiResponse readOrgChildren(String orgId, ApiRequest<ReadChildrenRequestBody> request, String accessToken) {
        authUtils.validateCallerAuthenticationAndFetchUserId(accessToken);
        OrgHierarchyDto root = orgHierarchyRepository.findById(orgId)
                .orElseThrow(() -> new ServiceException(ErrorCode.ORG_NOT_FOUND, "Org " + orgId + " is not in the hierarchy"));
        ReadChildrenRequestBody body = request == null || request.getRequest() == null
                ? new ReadChildrenRequestBody() : request.getRequest();
        int limit = body.limitOrDefault();
        boolean withDescendantCounts = body.countDescendantsOrDefault();
        boolean levelRequired = body.levelRequiredOrDefault();
        String query = AppUtil.isBlank(body.getQuery()) ? null : body.getQuery().trim();
        String queryPattern = query == null ? null : AppUtil.likeContainsPattern(query);
        // explicit depth always wins; otherwise a search reaches the whole subtree (it has to, to find a match
        // anywhere under orgId), but a plain, filter-less read stays the original "direct children only" default -
        // so a caller who just passes orgId never gets the whole hierarchy back without asking for it.
        int depthLimit = body.getDepth() != null ? Math.min(body.getDepth(), maxDepth) : query != null ? maxDepth : 1;
        // the org's position in the whole hierarchy (an L0 root is level 1) - not relative to this call's orgId, so
        // calling /read on a deeply-nested org still reports that org's real, absolute level
        List<String> ancestorChain = orgHierarchyRepository.chainUp(orgId);
        int rootLevel = ancestorChain.size();
        Map<Integer, HierarchyLevelRecord> levels = orgHierarchyLevelService.levelMapFor(rootOrgIdOf(ancestorChain));

        List<OrgHierarchyDtoWithDepth> page;
        if (body.getOffset() != null) {
            // offset wins over cursor when both are somehow given - see ReadChildrenRequestBody#offset
            page = orgHierarchyRepository.findDescendantsByOffset(orgId, depthLimit, query, queryPattern, body.getOffset(), limit + 1);
        } else {
            String[] cursor = decodeCursor(body.getCursor());
            page = orgHierarchyRepository.findDescendants(orgId, depthLimit, query, queryPattern,
                    cursor == null ? null : cursor[0], cursor == null ? null : cursor[1], limit + 1);
        }
        boolean hasMore = page.size() > limit;
        List<OrgHierarchyDtoWithDepth> items = hasMore ? page.subList(0, limit) : page;

        int deepestLevelReached = rootLevel;
        List<ChildOrgSummary> summaries = new ArrayList<>();
        for (OrgHierarchyDtoWithDepth item : items) {
            OrgHierarchyDto node = item.node();
            Integer descendantCount = withDescendantCounts ? orgHierarchyRepository.countDescendants(node.id()) : null;
            int childLevel = rootLevel + item.depth();
            deepestLevelReached = Math.max(deepestLevelReached, childLevel);
            summaries.add(new ChildOrgSummary(node.id(), node.name(), node.idle(), node.childCount(), descendantCount,
                    childLevel, orgHierarchyLevelService.levelNameOf(levels, childLevel)));
        }
        // a cursor only makes sense when paging by cursor; an offset-paging caller computes its own next offset
        String nextCursor = hasMore && body.getOffset() == null
                ? AppUtil.encodeCursor(lastOf(items).node().name(), lastOf(items).node().id()) : null;
        ReadChildrenResponse result = new ReadChildrenResponse(root.id(), root.name(), root.idle(), root.childCount(),
                rootLevel, orgHierarchyLevelService.levelNameOf(levels, rootLevel), summaries,
                // the blueprint starts at the queried org's own level - e.g. a level-2 org reports L2..L10, never L1
                levelRequired ? orgHierarchyLevelService.levelsBlueprint(levels, rootLevel) : null,
                levelRequired ? deepestLevelReached : null,
                levelRequired ? Constants.MAX_ALLOWED_LEVEL : null,
                limit, hasMore, nextCursor);
        return versionedResponse(Constants.API_READ, result);
    }

    @Override
    @Transactional(value = OrgHierarchyTransactionConfig.ORG_HIERARCHY_TX_MANAGER, readOnly = true)
    public ApiResponse readOrgHierarchy(String orgId, Integer depth, boolean levelRequired, String accessToken) {
        authUtils.validateCallerAuthenticationAndFetchUserId(accessToken);
        orgHierarchyValidator.requireExisting(orgHierarchyRepository.findById(orgId).isPresent(), "Org " + orgId + " is not in the hierarchy");

        List<OrgHierarchyDto> rows = orgHierarchyRepository.findSubtree(orgId); // already bounded by org-hierarchy.max-depth
        Map<String, OrgHierarchyDto> byId = new LinkedHashMap<>();
        Map<String, List<OrgHierarchyDto>> childrenByParent = new LinkedHashMap<>();
        for (OrgHierarchyDto node : rows) {
            byId.put(node.id(), node);
            if (node.parentId() != null) {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
            }
        }
        // the org's own absolute level in the whole hierarchy (an L0 root is level 1), so a /complete call on a
        // deeply-nested org still reports its real level, not 1
        List<String> ancestorChain = orgHierarchyRepository.chainUp(orgId);
        int rootLevel = ancestorChain.size();
        Map<Integer, HierarchyLevelRecord> levels = orgHierarchyLevelService.levelMapFor(rootOrgIdOf(ancestorChain));

        OrgTreeNode tree = buildTree(byId.get(orgId), childrenByParent, depth, 1, rootLevel, levels);
        // levelRequired defaults to true for /complete, unlike /read's default-false - see read-and-complete-hierarchy-spec.md section 6.1,
        // resolved by CompleteHierarchyRequestParams#levelRequiredOrDefault before reaching this service method
        HierarchyResponse result = new HierarchyResponse(tree, countNodes(tree),
                // the blueprint starts at the queried org's own level - e.g. a level-2 org reports L2..L10, never L1
                levelRequired ? orgHierarchyLevelService.levelsBlueprint(levels, rootLevel) : null,
                levelRequired ? orgHierarchyLevelService.maxLevelIn(tree) : null,
                levelRequired ? Constants.MAX_ALLOWED_LEVEL : null);
        return versionedResponse(Constants.API_HIERARCHY, result);
    }

    private OrgTreeNode buildTree(OrgHierarchyDto node, Map<String, List<OrgHierarchyDto>> childrenByParent, Integer depthLimit,
                                   int currentDepth, int level, Map<Integer, HierarchyLevelRecord> levels) {
        List<OrgTreeNode> associations = new ArrayList<>();
        if (depthLimit == null || currentDepth < depthLimit) {
            for (OrgHierarchyDto child : childrenByParent.getOrDefault(node.id(), Collections.emptyList())) {
                associations.add(buildTree(child, childrenByParent, depthLimit, currentDepth + 1, level + 1, levels));
            }
        }
        return new OrgTreeNode(node.id(), node.name(), node.idle(), node.childCount(), level,
                orgHierarchyLevelService.levelNameOf(levels, level), associations);
    }

    /** {@code rootOrgIdOf(chainUp(orgId))} - {@code chainUp} returns {@code orgId} itself first, the L0 root last. */
    private static String rootOrgIdOf(List<String> ancestorChainSelfFirst) {
        return ancestorChainSelfFirst.get(ancestorChainSelfFirst.size() - 1);
    }

    private static int countNodes(OrgTreeNode node) {
        int count = 1;
        for (OrgTreeNode child : node.getAssociations()) {
            count += countNodes(child);
        }
        return count;
    }

    private static String[] decodeCursor(String cursor) {
        if (AppUtil.isBlank(cursor)) {
            return null;
        }
        try {
            return AppUtil.decodeCursor(cursor);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Malformed cursor: " + cursor);
        }
    }

    /** Re-reads {@code id} under the lock and refuses with CONCURRENT_MODIFICATION if its parent moved since the pre-check. */
    private OrgHierarchyDto requireUnchangedParent(String id, String parentIdBeforeLock) {
        OrgHierarchyDto node = orgHierarchyRepository.findById(id).orElseThrow(() -> notFound(id));
        if (!parentIdBeforeLock.equals(node.parentId())) {
            throw new ServiceException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The parent of " + id + " changed while this request was waiting; retry");
        }
        return node;
    }

    private static String nameOrId(OrganisationRecord record) {
        return AppUtil.defaultIfBlank(record.name(), record.id());
    }

    // ================================================================ Kafka event publishing (post-commit only)

    /**
     * Zero-phantom-events rule: {@code publishTask} only ever runs after the enclosing PostgreSQL transaction has
     * actually committed - if it rolls back, nothing is published. Runs immediately when there is no active
     * transaction (e.g. a direct unit-test call), so callers never need their own fallback.
     */
    private void publishAfterCommit(Runnable publishTask) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishTask.run();
                }
            });
        } else {
            publishTask.run();
        }
    }

    private static HierarchyEvent createLinkEvent(String childId, String parentId, String name, String userId) {
        return new HierarchyEvent(childId, parentId, null, name, false, Constants.EVENT_TYPE_LINK, System.currentTimeMillis(), userId);
    }

    private static HierarchyEvent createMoveEvent(String orgId, String newParentId, String oldParentId, String name, String userId) {
        return new HierarchyEvent(orgId, newParentId, oldParentId, name, false, Constants.EVENT_TYPE_MOVE, System.currentTimeMillis(), userId);
    }

    private static HierarchyEvent createUnlinkEvent(String orgId, String prevParentId, String name, String userId) {
        return new HierarchyEvent(orgId, null, prevParentId, name, true, Constants.EVENT_TYPE_UNLINK, System.currentTimeMillis(), userId);
    }

    private static TreeSet<String> sorted(String... ids) {
        TreeSet<String> set = new TreeSet<>();
        for (String id : ids) {
            set.add(id);
        }
        return set;
    }

    private static TreeSet<String> sorted(Collection<String> ids) {
        return new TreeSet<>(ids);
    }

    private static OrgHierarchyDtoWithDepth lastOf(List<OrgHierarchyDtoWithDepth> items) {
        return items.get(items.size() - 1);
    }

    private static ServiceException notFound(String id) {
        return new ServiceException(ErrorCode.ORG_NOT_FOUND, "Org not found in hierarchy: " + id);
    }

    private static ApiResponse versionedResponse(String apiId, Object result) {
        return AppUtil.versionedResponse(apiId, result);
    }
}
