package com.hierarchyhub.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;

@Getter
@Accessors(fluent = true)
@AllArgsConstructor
public final class OrgHierarchyDto {

    private final String id;
    private final String name;
    private final String parentId;
    private final boolean idle;
    private final int childCount;
    private final Instant createdAt;
    private final String createdBy;
    private final Instant updatedAt;
    private final String updatedBy;
}
