package com.hierarchyhub.service;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.SyncParentPathRequestBody;
import com.hierarchyhub.dto.SyncParentPathResponse;
import com.hierarchyhub.model.ApiResponse;

public interface OrgUpdateService {

    SyncParentPathResponse resyncParentPath(String orgId, String userId);

    /** Authorizes the caller, repairs a single org's {@code parentPathId}, and returns the versioned API envelope. */
    ApiResponse repairOrgParentPath(String orgId, String accessToken);

    /** Authorizes the caller, runs a deep subtree {@code parentPathId} sync from {@code rootOrgId} per {@code request}'s flags, and returns the versioned API envelope. */
    ApiResponse syncSubtreeParentPaths(String rootOrgId, ApiRequest<SyncParentPathRequestBody> request, String accessToken);
}
