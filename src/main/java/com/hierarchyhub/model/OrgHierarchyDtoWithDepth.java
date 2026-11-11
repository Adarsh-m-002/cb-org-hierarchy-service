package com.hierarchyhub.model;

/** One row from a descendants query, paired with how many hops it is below the queried org (1 = a direct child). */
public record OrgHierarchyDtoWithDepth(OrgHierarchyDto node, int depth) {
}
