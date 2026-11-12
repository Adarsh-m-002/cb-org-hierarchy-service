package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
public class OrgTreeNode {

    private final String id;
    private final String name;
    private final boolean idle;
    private final int childCount;
    private final int level;
    private final String levelName;
    private final List<OrgTreeNode> associations;
}
