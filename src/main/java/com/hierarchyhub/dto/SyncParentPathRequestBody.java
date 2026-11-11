package com.hierarchyhub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** {@code POST /admin/parent-path/sync/{rootOrgId}}'s {@code "request"} body - every field optional, resolved against its documented default in the service layer. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SyncParentPathRequestBody {

    /** Whether to cascade down to all sub-children recursively. Defaults to {@code true}. */
    private Boolean includeChildren;

    /** If {@code true}, only updates organisations with zero children. Defaults to {@code false}. */
    private Boolean onlyLeaves;

    /** If {@code false}, skips the external call when the computed path already matches Cassandra's. Defaults to {@code false}. */
    private Boolean forceUpdate;
}
