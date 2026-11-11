package com.hierarchyhub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MoveOrgRequestBody {

    @NotBlank(message = "orgId is required")
    private String orgId;

    @NotBlank(message = "newParentOrgId is required")
    private String newParentOrgId;
}
