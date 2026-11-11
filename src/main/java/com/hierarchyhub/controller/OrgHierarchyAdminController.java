package com.hierarchyhub.controller;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.SyncParentPathRequestBody;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.service.OrgUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/org/v2/hierarchy/admin")
public class OrgHierarchyAdminController {

    private final OrgUpdateService orgUpdateService;

    public OrgHierarchyAdminController(OrgUpdateService orgUpdateService) {
        this.orgUpdateService = orgUpdateService;
    }

    /** Targeted single-org repair (or a manual re-trigger of what the Kafka consumer already does automatically). */
    @PostMapping("/parent-path/{orgId}")
    public ResponseEntity<ApiResponse> repairSingleOrg(@PathVariable String orgId,
                                                        @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgUpdateService.repairOrgParentPath(orgId, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    /** Deep subtree sync: cascades from {@code rootOrgId} to every sub-child (or just the single node if {@code includeChildren=false}). */
    @PostMapping("/parent-path/sync/{rootOrgId}")
    public ResponseEntity<ApiResponse> syncSubtreeHierarchy(@PathVariable String rootOrgId,
                                                             @RequestBody(required = false) ApiRequest<SyncParentPathRequestBody> request,
                                                             @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgUpdateService.syncSubtreeParentPaths(rootOrgId, request, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}
