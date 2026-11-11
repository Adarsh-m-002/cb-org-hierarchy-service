package com.hierarchyhub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LinkOrgRequestBody {

    @NotBlank(message = "parentOrgId is required")
    private String parentOrgId;

    @NotEmpty(message = "associations must list at least one child")
    @Valid
    private List<AssociationRef> associations;
}
