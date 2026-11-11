package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
public class ReadChildrenResponse {

    private final String id;
    private final String name;
    private final boolean idle;
    private final int childCount;
    private final int level;
    private final String levelName;
    private final List<ChildOrgSummary> associations;
    /** Only present when the caller asked for {@code levelRequired} - see {@link #maxAllowedLevel}/{@link #levels}. */
    private final List<HierarchyLevelItem> levels;
    private final Integer maxCreatedLevel;
    private final Integer maxAllowedLevel;
    private final int limit;
    private final boolean hasMore;
    private final String nextCursor;
}
