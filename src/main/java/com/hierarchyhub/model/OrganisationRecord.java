package com.hierarchyhub.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@AllArgsConstructor
public final class OrganisationRecord {

    private final String id;
    private final String name;
    private final boolean active;
    private final String ministryId;
    private final Integer organizationType;
    private final String ministryOrStateType;
    private final String parentPathId;
}
