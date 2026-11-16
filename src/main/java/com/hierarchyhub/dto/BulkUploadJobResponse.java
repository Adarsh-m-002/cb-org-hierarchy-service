package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** {@code POST /bulk/link}'s response - supersedes {@code BulkLinkUploadResponse} with a real, queryable {@code jobId} (sunbird-cb-ext's own {@code progress} endpoint calls it {@code identifier}). */
@Getter
@AllArgsConstructor
public class BulkUploadJobResponse {

    private final String jobId;
    private final String fileName;
    private final String createdBy;
    private final String dateCreatedOn;
    private final String rootOrgId;
    private final String status;
}
