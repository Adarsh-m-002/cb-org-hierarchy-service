package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BatchLinkExecutionResult {

    private final List<String> succeededChildOrganisationIds;
    private final List<OrgLinkErrorDetail> failureErrors;
}
