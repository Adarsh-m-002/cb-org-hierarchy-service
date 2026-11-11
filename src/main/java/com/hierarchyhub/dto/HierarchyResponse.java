package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
public class HierarchyResponse {

    @JsonUnwrapped
    private final OrgTreeNode org;
    private final int count;
    /** Only present when the caller asked for {@code levelRequired} (default true) - see {@link #maxCreatedLevel}/{@link #maxAllowedLevel}. */
    private final List<HierarchyLevelItem> levels;
    private final Integer maxCreatedLevel;
    private final Integer maxAllowedLevel;
}
