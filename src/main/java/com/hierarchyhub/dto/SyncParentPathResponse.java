package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Outcome of one {@code parentPathId} projection/sync run - returned by both admin APIs and built internally by the Kafka consumer's single-org projection. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncParentPathResponse {

    private String rootOrgId;
    private Integer totalNodesInBranch;
    private Integer orgsUpdated;
    private Integer orgsSkipped;
    private Integer orgsFailed;
    private Long durationMs;
}
