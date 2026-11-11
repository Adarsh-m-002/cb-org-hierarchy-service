package com.hierarchyhub.dto;

import lombok.Getter;

@Getter
public class MoveOrgResponse {

    private final String orgId;
    private final String oldParentId;
    private final String newParentId;
    private final boolean changed;
    private final String message;

    public MoveOrgResponse(String orgId, String oldParentId, String newParentId, boolean changed, String message) {
        this.orgId = orgId;
        this.oldParentId = oldParentId;
        this.newParentId = newParentId;
        this.changed = changed;
        this.message = message;
    }
}
