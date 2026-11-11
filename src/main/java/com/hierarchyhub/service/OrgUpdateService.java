package com.hierarchyhub.service;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.SyncParentPathRequestBody;
import com.hierarchyhub.dto.SyncParentPathResponse;
import com.hierarchyhub.model.ApiResponse;

public interface OrgUpdateService {

    /**
     * Recomputes and synchronizes {@code parentPathId} for {@code orgId} and its descendants, with every optional
     * flag at its default (cascade to all children, diff-check enabled) and no caller to authorize - used by the
     * Kafka consumer, which has no access token to check.
     */
    SyncParentPathResponse resyncParentPath(String orgId);

    /** Authorizes the caller, repairs a single org's {@code parentPathId}, and returns the versioned API envelope. */
    ApiResponse repairOrgParentPath(String orgId, String accessToken);

    /** Authorizes the caller, runs a deep subtree {@code parentPathId} sync from {@code rootOrgId} per {@code request}'s flags, and returns the versioned API envelope. */
    ApiResponse syncSubtreeParentPaths(String rootOrgId, ApiRequest<SyncParentPathRequestBody> request, String accessToken);
}
