package com.hierarchyhub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class UpdateHierarchyLevelsRequestBody {

    @NotEmpty(message = "levels list must not be empty")
    @Valid
    private List<HierarchyLevelItem> levels;
}
