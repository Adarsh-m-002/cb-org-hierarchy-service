package com.hierarchyhub.controller;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.CompleteHierarchyRequestParams;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.LinkOrgRequestBody;
import com.hierarchyhub.dto.MoveOrgRequestBody;
import com.hierarchyhub.dto.ReadChildrenRequestBody;
import com.hierarchyhub.dto.UnlinkOrgRequestBody;
import com.hierarchyhub.dto.UpdateHierarchyLevelsRequestBody;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.service.BulkLinkFileService;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.service.OrgHierarchyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/org/v2/hierarchy")
public class OrgHierarchyController {

    private final OrgHierarchyService orgHierarchyService;
    private final OrgHierarchyLevelService orgHierarchyLevelService;
    private final BulkLinkFileService bulkLinkFileService;

    public OrgHierarchyController(OrgHierarchyService orgHierarchyService, OrgHierarchyLevelService orgHierarchyLevelService,
                                   BulkLinkFileService bulkLinkFileService) {
        this.orgHierarchyService = orgHierarchyService;
        this.orgHierarchyLevelService = orgHierarchyLevelService;
        this.bulkLinkFileService = bulkLinkFileService;
    }

    @PostMapping("/link")
    public ResponseEntity<ApiResponse> link(@Valid @RequestBody ApiRequest<LinkOrgRequestBody> request,
                                             @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyService.linkOrgToHierarchy(request, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/unlink")
    public ResponseEntity<ApiResponse> unlink(@Valid @RequestBody ApiRequest<UnlinkOrgRequestBody> request,
                                              @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyService.unlinkOrgFromHierarchy(request, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/move")
    public ResponseEntity<ApiResponse> move(@Valid @RequestBody ApiRequest<MoveOrgRequestBody> request,
                                             @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyService.moveOrgInHierarchy(request, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/{orgId}/complete")
    public ResponseEntity<ApiResponse> getCompleteHierarchy(@PathVariable String orgId,
                                                            @ModelAttribute CompleteHierarchyRequestParams params,
                                                            @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyService.readOrgHierarchy(orgId, params.getDepth(), params.levelRequiredOrDefault(), accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/read/{orgId}")
    public ResponseEntity<ApiResponse> read(@PathVariable String orgId,
                                             @Valid @RequestBody(required = false) ApiRequest<ReadChildrenRequestBody> request,
                                             @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyService.readOrgChildren(orgId, request, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping(value = "/bulk/link/{rootOrgId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> bulkLink(@RequestParam("file") MultipartFile file,
            @PathVariable("rootOrgId") String rootOrgId, @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = bulkLinkFileService.initiate(file, rootOrgId, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PatchMapping("/levels/{rootOrgId}")
    public ResponseEntity<ApiResponse> updateLevels(@PathVariable String rootOrgId,
                                                     @Valid @RequestBody ApiRequest<UpdateHierarchyLevelsRequestBody> request,
                                                     @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyLevelService.updateHierarchyLevels(rootOrgId, request, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/levels/{rootOrgId}")
    public ResponseEntity<ApiResponse> readLevels(@PathVariable String rootOrgId,
                                                   @RequestHeader(Constants.AUTH_TOKEN_HEADER) String accessToken) {
        ApiResponse response = orgHierarchyLevelService.readHierarchyLevels(rootOrgId, accessToken);
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}
