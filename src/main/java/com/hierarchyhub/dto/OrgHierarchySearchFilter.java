package com.hierarchyhub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrgHierarchySearchFilter {

    private String parentOrgId;

    private String excludeOrgWithChildren;

    @Min(value = 1, message = "level must be between 1 and 10")
    @Max(value = 10, message = "level must be between 1 and 10")
    private Integer level;
}
