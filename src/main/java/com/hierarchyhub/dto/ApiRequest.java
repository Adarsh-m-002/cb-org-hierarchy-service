package com.hierarchyhub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The {@code {"request": {...}}} envelope every write endpoint in this service shares - one generic wrapper instead
 * of a near-duplicate per-endpoint {@code XRequest} class. {@code T} is the endpoint's own request body
 * (e.g. {@link LinkOrgRequestBody}, {@link UpdateHierarchyLevelsRequestBody}).
 */
@Getter
@Setter
@NoArgsConstructor
public class ApiRequest<T> {

    @NotNull(message = "request is required")
    @Valid
    private T request;
}
