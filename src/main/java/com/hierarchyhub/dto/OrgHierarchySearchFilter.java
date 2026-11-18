package com.hierarchyhub.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrgHierarchySearchFilter {

    private String identifier;

    private String excludeOrganizationWithChildren;
}
