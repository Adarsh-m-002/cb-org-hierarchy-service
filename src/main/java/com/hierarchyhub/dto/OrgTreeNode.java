package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

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
