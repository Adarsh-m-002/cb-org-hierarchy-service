package com.hierarchyhub.service;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.BatchLinkExecutionResult;
import com.hierarchyhub.dto.LinkOrgRequestBody;
import com.hierarchyhub.dto.MoveOrgRequestBody;
import com.hierarchyhub.dto.ReadChildrenRequestBody;
import com.hierarchyhub.dto.UnlinkOrgRequestBody;
import com.hierarchyhub.model.ApiResponse;

import java.util.List;

public interface OrgHierarchyService {

    ApiResponse linkOrgToHierarchy(ApiRequest<LinkOrgRequestBody> request, String accessToken);

    BatchLinkExecutionResult linkChildrenUnderParentBatch(String parentOrgId, List<String> childOrganisationIds, String userId);

    /** Moves {@code request.orgId} (and its whole branch) to {@code request.newParentId}, which must share its ministry/state. */
    ApiResponse moveOrgInHierarchy(ApiRequest<MoveOrgRequestBody> request, String accessToken);

    ApiResponse unlinkOrgFromHierarchy(ApiRequest<UnlinkOrgRequestBody> request, String accessToken);

    ApiResponse readOrgChildren(String orgId, ApiRequest<ReadChildrenRequestBody> request, String accessToken);

    ApiResponse readOrgHierarchy(String orgId, Integer depth, boolean levelRequired, String accessToken);
}
