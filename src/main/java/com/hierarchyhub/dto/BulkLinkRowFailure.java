package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public final class BulkLinkRowFailure {

    private final Integer rowNumber;
    private final String parentOrgId;
    private final String childOrgId;
    private final String reason;

    @Override
    public String toString() {
        return (rowNumber == null ? "" : "row " + rowNumber + ": ") + parentOrgId + " -> " + childOrgId + " (" + reason + ")";
    }
}
