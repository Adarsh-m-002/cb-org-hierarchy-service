package com.hierarchyhub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = Constants.MAX_LINK_ASSOCIATIONS_PER_REQUEST, message = "associations: at most " + Constants.MAX_LINK_ASSOCIATIONS_PER_REQUEST + " children per request - use /bulk/link for larger batches")
    private List<AssociationRef> associations;
}
