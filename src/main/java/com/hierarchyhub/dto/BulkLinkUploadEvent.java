package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The {@code /bulk/link} Kafka hand-off payload - everything the async consumer needs to pick the job back up without re-reading the request. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkLinkUploadEvent {

    private String rootOrgId;
    private String identifier;
    private String fileName;
    private String objectKey;
    private String createdBy;
}
