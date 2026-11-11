package com.hierarchyhub.model;

/** One configured level (1..10) of a Ministry/State's hierarchy - its display name and description, read from {@code org_hierarchy_level}. */
public record HierarchyLevelRecord(int level, String name, String description) {
}
