package com.hierarchyhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Pure POJO request body: zero business logic or default-resolution methods - see {@code OrgHierarchyServiceImpl}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UnlinkOrgRequestBody {

    @NotBlank(message = "orgId is required")
    private String orgId;

    private Boolean requireLeaf;
}
