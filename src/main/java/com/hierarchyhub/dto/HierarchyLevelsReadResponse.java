package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
public class HierarchyLevelsReadResponse {

    private final String rootOrgId;
    private final int maxAllowedLevel;
    private final List<HierarchyLevelItem> levels;
}
