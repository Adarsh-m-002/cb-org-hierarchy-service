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
import org.springframework.util.StringUtils;

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
    public SyncParentPathResponse resyncParentPath(String orgId) {
        return computeAndSyncParentPaths(orgId, new SyncParentPathRequestBody(true, false, false), Constants.SYSTEM_USER);
    }

    @Override
    public ApiResponse repairOrgParentPath(String orgId, String accessToken) {
        authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        SyncParentPathResponse result = resyncParentPath(orgId);
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

        List<PendingPathUpdate> candidates;
        int totalNodesInBranch;
        if (!includeChildren || rootNode.childCount() == 0) {
            // Leaf fast path: a single node is its own "subtree" - no recursive CTE needed.
            List<String> ancestorsRootFirst = ancestorsAboveAndIncluding(rootOrgId);
            String path = ParentPaths.of(Collections.emptyList(), ancestorsRootFirst, Constants.PATH_SEPARATOR);
            candidates = Collections.singletonList(new PendingPathUpdate(rootOrgId, path));
            totalNodesInBranch = 1;
        } else {
            List<String> above = aboveAncestorsOf(rootOrgId);
            candidates = new ArrayList<>();
            orgHierarchyRepository.streamSubtreeWithPaths(rootOrgId, (node, depth, pathIds) -> {
                if (onlyLeaves && node.childCount() != 0) {
                    return;
                }
                candidates.add(new PendingPathUpdate(node.id(), ParentPaths.of(above, pathIds, Constants.PATH_SEPARATOR)));
            });
            totalNodesInBranch = candidates.size();
        }

        SyncOutcome outcome = applyInChunks(candidates, forceUpdate);
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

    /**
     * Diff-checks and dispatches {@code candidates} in {@code org-hierarchy.parent-path.batch-size} chunks: one
     * batched Cassandra read per chunk (never one read per org), then one concurrent HTTP dispatch per chunk across
     * the bounded {@code parentPathExecutor} pool.
     */
    private SyncOutcome applyInChunks(List<PendingPathUpdate> candidates, boolean forceUpdate) {
        SyncOutcome outcome = SyncOutcome.ZERO;
        int chunkSize = Math.max(batchSize, 1); // never let a misconfigured/unset 0 hang this loop
        for (int start = 0; start < candidates.size(); start += chunkSize) {
            List<PendingPathUpdate> chunk = candidates.subList(start, Math.min(start + chunkSize, candidates.size()));
            outcome = outcome.plus(applyChunk(chunk, forceUpdate));
        }
        return outcome;
    }

    private SyncOutcome applyChunk(List<PendingPathUpdate> chunk, boolean forceUpdate) {
        Map<String, OrganisationRecord> existingRecords = forceUpdate ? Collections.emptyMap()
                : cassandraUtils.fetchBatchOrganisationsFromTable(idsOf(chunk));

        int skipped = 0;
        List<PendingPathUpdate> toDispatch = new ArrayList<>(chunk.size());
        for (PendingPathUpdate candidate : chunk) {
            if (!forceUpdate && alreadyMatches(existingRecords.get(candidate.orgId()), candidate.parentPathId())) {
                skipped++;
            } else {
                toDispatch.add(candidate);
            }
        }
        if (toDispatch.isEmpty()) {
            return new SyncOutcome(0, skipped, 0);
        }

        List<CompletableFuture<Boolean>> dispatched = new ArrayList<>(toDispatch.size());
        for (PendingPathUpdate candidate : toDispatch) {
            dispatched.add(CompletableFuture.supplyAsync(
                    () -> updateParentPathId(candidate.orgId(), candidate.parentPathId()), parentPathExecutor));
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

    private boolean updateParentPathId(String organisationId, String parentPathId) {
        Map<String, Object> requestBody = requestBody(organisationId, parentPathId);
        Map<String, String> headers = requestHeaders();
        String targetUrl = externalServiceProperties.getOrgServiceHost() + externalServiceProperties.getOrgUpdateEndpoint();

        for (int attempt = 1; attempt <= externalServiceProperties.getMaxAttempts(); attempt++) {
            try {
                Map<String, Object> response = outboundRequestHandlerService.fetchResultUsingPatch(targetUrl, requestBody, headers);
                if (Constants.RESPONSE_CODE_OK.equalsIgnoreCase(String.valueOf(response.get(Constants.RESPONSE_CODE_KEY)))) {
                    log.debug("Successfully updated parentPathId for orgId={} to '{}'", organisationId, parentPathId);
                    return true;
                }
                log.warn("Attempt {}/{} did not succeed for orgId={}: {}", attempt, externalServiceProperties.getMaxAttempts(), organisationId, response);
            } catch (Exception e) {
                log.warn("Attempt {}/{} failed for orgId={} calling {}: {}", attempt, externalServiceProperties.getMaxAttempts(), organisationId, targetUrl, e.getMessage());
            }
            if (attempt == externalServiceProperties.getMaxAttempts()) {
                log.error("All {} attempts exhausted to update parentPathId for orgId={}", externalServiceProperties.getMaxAttempts(), organisationId);
                return false;
            }
            if (!sleep(externalServiceProperties.getRetryIntervalMs())) {
                return false;
            }
        }
        return false;
    }

    private static Map<String, Object> requestBody(String organisationId, String parentPathId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put(Constants.ORGANIZATION_ID, organisationId);
        requestBody.put(Constants.PARENT_PATH_ID_KEY, parentPathId);
        return requestBody;
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (StringUtils.hasText(externalServiceProperties.getAuthToken())) {
            headers.put(Constants.X_AUTH_USER_HEADER, externalServiceProperties.getAuthUserToken());
            headers.put(Constants.AUTHORIZATION_HEADER, externalServiceProperties.getAuthToken());
        }
        return headers;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean alreadyMatches(OrganisationRecord existing, String computedPath) {
        return existing != null && computedPath.equals(existing.parentPathId());
    }

    private static List<String> idsOf(List<PendingPathUpdate> candidates) {
        List<String> ids = new ArrayList<>(candidates.size());
        for (PendingPathUpdate candidate : candidates) {
            ids.add(candidate.orgId());
        }
        return ids;
    }
}
