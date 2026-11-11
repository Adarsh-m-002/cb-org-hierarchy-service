package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Pure event contract published to {@code org-hierarchy-edges}: fields, constructors, getters/setters only - assembly lives in the service layer. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HierarchyEvent {

    /** Identifier of the organisation whose hierarchy edge changed. Partition key. */
    private String orgId;

    /** New parent organisation ID (null if unlinked). */
    private String parentId;

    /** Previous parent organisation ID (populated during move and unlink; null for a new link). */
    private String prevParentId;

    /** Display name of the organisation. */
    private String name;

    /** Whether the organisation is currently idle/unparented. */
    private Boolean idle;

    /** Operation type - one of {@code Constants.EVENT_TYPE_*}. */
    private String eventType;

    /** Epoch timestamp in milliseconds. */
    private Long timestamp;

    /** Authenticated user who performed the action. */
    private String userId;
}
