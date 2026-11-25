package com.hierarchyhub.service.impl;

import com.hierarchyhub.config.ExternalServiceProperties;
import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.SyncParentPathRequestBody;
import com.hierarchyhub.dto.SyncParentPathResponse;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.model.PendingPathUpdate;
import com.hierarchyhub.model.SyncOutcome;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.OrgUpdateService;
import com.hierarchyhub.util.AppUtil;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CassandraUtils;
import com.hierarchyhub.util.ParentPaths;
import org.igot.common.service.OutboundRequestHandlerServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
public class OrgUpdateServiceImpl implements OrgUpdateService {

    private static final Logger log = LoggerFactory.getLogger(OrgUpdateServiceImpl.class);

    private final OrgHierarchyRepository orgHierarchyRepository;
    private final CassandraUtils cassandraUtils;
    private final OutboundRequestHandlerServiceImpl outboundRequestHandlerService;
    private final ExternalServiceProperties externalServiceProperties;
    private final AuthUtils authUtils;
    private final ExecutorService parentPathExecutor;

    @Value("${org-hierarchy.parent-path.batch-size}")
    private int batchSize;

    public OrgUpdateServiceImpl(OrgHierarchyRepository orgHierarchyRepository, CassandraUtils cassandraUtils,
                                 OutboundRequestHandlerServiceImpl outboundRequestHandlerService,
                                 ExternalServiceProperties externalServiceProperties, AuthUtils authUtils,
                                 @Qualifier(Constants.PARENT_PATH_EXECUTOR) ExecutorService parentPathExecutor) {
        this.orgHierarchyRepository = orgHierarchyRepository;
        this.cassandraUtils = cassandraUtils;
        this.outboundRequestHandlerService = outboundRequestHandlerService;
        this.externalServiceProperties = externalServiceProperties;
        this.authUtils = authUtils;
        this.parentPathExecutor = parentPathExecutor;
    }

    @Override
    public SyncParentPathResponse resyncParentPath(String orgId, String userId) {
        return computeAndSyncParentPaths(orgId, new SyncParentPathRequestBody(true, false, false), userId);
    }

    @Override
    public SyncParentPathResponse resyncSingleOrgParentPath(String orgId, String userId) {
        return computeAndSyncParentPaths(orgId, new SyncParentPathRequestBody(false, false, true), userId);
    }

    @Override
    public ApiResponse repairOrgParentPath(String orgId, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        SyncParentPathResponse result = resyncParentPath(orgId, userId);
        return AppUtil.versionedResponse(Constants.API_ADMIN_PARENT_PATH, result);
    }

    @Override
    public ApiResponse syncSubtreeParentPaths(String rootOrgId, ApiRequest<SyncParentPathRequestBody> request, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        SyncParentPathRequestBody syncRequest = resolveSyncRequestBody(request);
        SyncParentPathResponse result = computeAndSyncParentPaths(rootOrgId, syncRequest, userId);
        return AppUtil.versionedResponse(Constants.API_ADMIN_PARENT_PATH_SYNC, result);
    }

    /** The whole {@code "request"} envelope is optional on this endpoint; a missing envelope or a missing inner body both fall back to every flag's documented default. */
    private static SyncParentPathRequestBody resolveSyncRequestBody(ApiRequest<SyncParentPathRequestBody> request) {
        return request == null ? new SyncParentPathRequestBody()
                : Objects.requireNonNullElseGet(request.getRequest(), SyncParentPathRequestBody::new);
    }

    private SyncParentPathResponse computeAndSyncParentPaths(String rootOrgId, SyncParentPathRequestBody request, String userId) {
        long startTime = System.currentTimeMillis();
        boolean includeChildren = request.getIncludeChildren() == null || request.getIncludeChildren();
        boolean onlyLeaves = Boolean.TRUE.equals(request.getOnlyLeaves());
        boolean forceUpdate = Boolean.TRUE.equals(request.getForceUpdate());
        log.info("Starting parentPathId sync: rootOrgId={}, includeChildren={}, onlyLeaves={}, forceUpdate={}, requestedBy={}",
                rootOrgId, includeChildren, onlyLeaves, forceUpdate, userId);

        var rootNode = orgHierarchyRepository.findById(rootOrgId)
                .orElseThrow(() -> new ServiceException(ErrorCode.ORG_NOT_FOUND, "Org not found in hierarchy: " + rootOrgId));

        List<PendingPathUpdate> pendingPathUpdates;
        int totalNodesInBranch;
        if (!includeChildren || rootNode.childCount() == 0) {
            // Leaf fast path: a single node is its own "subtree" - no recursive CTE needed.
            List<String> ancestorsRootFirst = ancestorsAboveAndIncluding(rootOrgId);
            String path = ParentPaths.of(Collections.emptyList(), ancestorsRootFirst, Constants.PATH_SEPARATOR);
            // An empty ancestor chain (ParentPaths.of's own "0 or 1 node" case) means either a genuine L0 root
            // (never had a parent, idle=false) or an org that was just unlinked/removed (idle=true per UNLINK/
            // CLEAR_SUBTREE) - the latter has no real hierarchyLevel to report, same as its already-empty path.
            boolean noRealAncestors = ancestorsRootFirst.size() <= 1;
            String hierarchyLevel = (noRealAncestors && rootNode.idle())
                    ? Constants.EMPTY_PARENT_PATH // same "" convention as parentPathId - this org has no real level, not "LevelZero"
                    : hierarchyLevelName(ancestorsRootFirst.size() - 1);
            pendingPathUpdates = Collections.singletonList(new PendingPathUpdate(rootOrgId, path, hierarchyLevel));
            totalNodesInBranch = 1;
        } else {
            List<String> above = aboveAncestorsOf(rootOrgId);
            int rootLevel = above.size();
            pendingPathUpdates = new ArrayList<>();
            orgHierarchyRepository.streamSubtreeWithPaths(rootOrgId, (node, depth, pathIds) -> {
                if (onlyLeaves && node.childCount() != 0) {
                    return;
                }
                String hierarchyLevel = hierarchyLevelName(rootLevel + depth);
                pendingPathUpdates.add(new PendingPathUpdate(node.id(), ParentPaths.of(above, pathIds, Constants.PATH_SEPARATOR), hierarchyLevel));
            });
            totalNodesInBranch = pendingPathUpdates.size();
        }

        SyncOutcome outcome = applyInChunks(pendingPathUpdates, forceUpdate, userId);
        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Completed parentPathId sync: rootOrgId={}, totalNodesInBranch={}, orgsUpdated={}, orgsSkipped={}, orgsFailed={}, durationMs={}",
                rootOrgId, totalNodesInBranch, outcome.updated(), outcome.skipped(), outcome.failed(), durationMs);

        return new SyncParentPathResponse(rootOrgId, totalNodesInBranch, outcome.updated(), outcome.skipped(), outcome.failed(), durationMs);
    }

    /** Ancestors of {@code orgId}, root first, {@code orgId} itself last - the full chain {@link ParentPaths#of} needs when there is no separate "above" prefix. */
    private List<String> ancestorsAboveAndIncluding(String orgId) {
        List<String> chain = new ArrayList<>(orgHierarchyRepository.chainUp(orgId)); // self first, root last
        Collections.reverse(chain);
        return chain;
    }

    /** Ancestors strictly above {@code rootOrgId} (root first), excluding {@code rootOrgId} itself - the prefix every descendant's own relative path gets appended to. */
    private List<String> aboveAncestorsOf(String rootOrgId) {
        List<String> rootFirst = ancestorsAboveAndIncluding(rootOrgId);
        return rootFirst.subList(0, rootFirst.size() - 1);
    }

    /** The lms-service org-update API's {@code hierarchyLevel} literal for an org {@code level} hops below the hierarchy root (root itself is level 0). */
    private static String hierarchyLevelName(int level) {
        if (level < 0 || level >= Constants.HIERARCHY_LEVEL_NAMES.size()) {
            log.warn("No hierarchyLevel name defined for level {} (expected 0-{}) - sending hierarchyLevel=null", level, Constants.MAX_ALLOWED_LEVEL);
            return null;
        }
        return Constants.HIERARCHY_LEVEL_NAMES.get(level);
    }

    /**
     * Diff-checks and dispatches {@code pendingPathUpdates} in {@code org-hierarchy.parent-path.batch-size} chunks:
     * one batched Cassandra read per chunk (never one read per org), then one concurrent HTTP dispatch per chunk
     * across the bounded {@code parentPathExecutor} pool.
     */
    private SyncOutcome applyInChunks(List<PendingPathUpdate> pendingPathUpdates, boolean forceUpdate, String userId) {
        SyncOutcome outcome = SyncOutcome.ZERO;
        int chunkSize = Math.max(batchSize, 1); // never let a misconfigured/unset 0 hang this loop
        for (int start = 0; start < pendingPathUpdates.size(); start += chunkSize) {
            List<PendingPathUpdate> chunk = pendingPathUpdates.subList(start, Math.min(start + chunkSize, pendingPathUpdates.size()));
            outcome = outcome.plus(applyChunk(chunk, forceUpdate, userId));
        }
        return outcome;
    }

    private SyncOutcome applyChunk(List<PendingPathUpdate> chunk, boolean forceUpdate, String userId) {
        Map<String, OrganisationRecord> existingRecords = forceUpdate ? Collections.emptyMap()
                : cassandraUtils.fetchBatchOrganisationsFromTable(idsOf(chunk));

        int skipped = 0;
        List<PendingPathUpdate> toDispatch = new ArrayList<>(chunk.size());
        for (PendingPathUpdate pendingPathUpdate : chunk) {
            if (!forceUpdate && alreadyMatches(existingRecords.get(pendingPathUpdate.orgId()), pendingPathUpdate.parentPathId())) {
                skipped++;
            } else {
                toDispatch.add(pendingPathUpdate);
            }
        }
        if (toDispatch.isEmpty()) {
            return new SyncOutcome(0, skipped, 0);
        }

        List<CompletableFuture<Boolean>> dispatched = new ArrayList<>(toDispatch.size());
        for (PendingPathUpdate pendingPathUpdate : toDispatch) {
            dispatched.add(CompletableFuture.supplyAsync(() -> updateParentPathId(pendingPathUpdate, userId), parentPathExecutor));
        }
        int updated = 0;
        int failed = 0;
        for (CompletableFuture<Boolean> future : dispatched) {
            if (Boolean.TRUE.equals(future.join())) {
                updated++;
            } else {
                failed++;
            }
        }
        return new SyncOutcome(updated, skipped, failed);
    }

    private boolean updateParentPathId(PendingPathUpdate pendingPathUpdate, String userId) {
        Map<String, Object> requestBody = requestBody(pendingPathUpdate, userId);
        String targetUrl = externalServiceProperties.getLmsServiceHost() + externalServiceProperties.getLmsOrgUpdateEndpoint();
        log.debug("Updating parentPathId for orgId={} to '{}' via {} with requestBody={}", pendingPathUpdate.orgId(), pendingPathUpdate.parentPathId(), targetUrl, requestBody);
        try {
            Map<String, Object> response = outboundRequestHandlerService.fetchResultUsingPatch(targetUrl, requestBody, Collections.emptyMap());
            if (Constants.RESPONSE_CODE_OK.equalsIgnoreCase(String.valueOf(response.get(Constants.RESPONSE_CODE_KEY)))) {
                log.debug("Successfully updated parentPathId for orgId={} to '{}'", pendingPathUpdate.orgId(), pendingPathUpdate.parentPathId());
                return true;
            }
            log.warn("Org-update did not succeed for orgId={}: {}", pendingPathUpdate.orgId(), response);
        } catch (Exception e) {
            log.warn("Org-update failed for orgId={} calling {}: {}", pendingPathUpdate.orgId(), targetUrl, e.getMessage());
        }
        return false;
    }

    private static Map<String, Object> requestBody(PendingPathUpdate pendingPathUpdate, String userId) {
        Map<String, Object> innerRequestBody = new LinkedHashMap<>();
        innerRequestBody.put(Constants.ORGANISATION_ID, pendingPathUpdate.orgId());
        innerRequestBody.put(Constants.PARENT_PATH_ID_KEY, pendingPathUpdate.parentPathId());
        innerRequestBody.put(Constants.HIERARCHY_LEVEL, pendingPathUpdate.hierarchyLevel());
        innerRequestBody.put(Constants.UPDATED_BY, userId);
        return Collections.singletonMap(Constants.REQUEST, innerRequestBody);
    }

    private static boolean alreadyMatches(OrganisationRecord existing, String computedPath) {
        return existing != null && !computedPath.isEmpty() && computedPath.equals(existing.parentPathId());
    }

    private static List<String> idsOf(List<PendingPathUpdate> pendingPathUpdates) {
        List<String> ids = new ArrayList<>(pendingPathUpdates.size());
        for (PendingPathUpdate pendingPathUpdate : pendingPathUpdates) {
            ids.add(pendingPathUpdate.orgId());
        }
        return ids;
    }
}
