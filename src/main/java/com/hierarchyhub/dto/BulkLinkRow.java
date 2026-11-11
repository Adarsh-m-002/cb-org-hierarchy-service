package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One valid, parsed data row of a {@code /bulk/link} sheet: the orgId pulled out of each of its two cells. */
@Getter
@AllArgsConstructor
public final class BulkLinkRow {

    private final Integer rowNumber;
    private final String parentOrgId;
    private final String childOrgId;

    public BulkLinkRow(String parentOrgId, String childOrgId) {
        this(null, parentOrgId, childOrgId);
    }
}
