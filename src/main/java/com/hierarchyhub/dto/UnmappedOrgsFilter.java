package com.hierarchyhub.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UnmappedOrgsFilter {

    private Integer status;
    private String ministryOrStateType;
    private String ministryOrStateId;
}
