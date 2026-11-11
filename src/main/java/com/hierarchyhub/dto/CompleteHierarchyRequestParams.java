package com.hierarchyhub.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Query params for {@code GET /{orgId}/complete}, bound as one object so a future param never grows the controller's own signature. */
@Getter
@Setter
@NoArgsConstructor
public class CompleteHierarchyRequestParams {

    private Integer depth;

    /** Whether to include the level blueprint array (and {@code maxCreatedLevel}/{@code maxAllowedLevel}) in the response; defaults to true. */
    private Boolean levelRequired;

    public boolean levelRequiredOrDefault() {
        return levelRequired == null || levelRequired;
    }
}
