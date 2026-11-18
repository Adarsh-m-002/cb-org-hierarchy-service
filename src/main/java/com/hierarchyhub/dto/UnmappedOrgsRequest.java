package com.hierarchyhub.dto;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UnmappedOrgsRequest {

    @Valid
    private UnmappedOrgsFilter filters;

    private String query;
    private Integer page;
    private Integer limit;
}
