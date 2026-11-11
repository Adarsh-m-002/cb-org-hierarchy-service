package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BulkLinkUploadResponse {

    private final String fileName;
    private final String createdBy;
    private final String dateCreatedOn;
    private final String rootOrgId;
    private final String status;
}
