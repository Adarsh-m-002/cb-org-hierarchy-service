package com.hierarchyhub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @Valid
    private List<AssociationRef> associations;
}
