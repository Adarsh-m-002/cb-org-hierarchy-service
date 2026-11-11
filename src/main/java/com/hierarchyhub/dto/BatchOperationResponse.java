package com.hierarchyhub.dto;

import lombok.Getter;

@Getter
public class BatchOperationResponse {

    private final String message;

    public BatchOperationResponse(String message) {
        this.message = message;
    }
}
