package com.hierarchyhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One level (1..10) of a root's hierarchy - the request item for a PATCH and a row of a read response's {@code levels} blueprint alike. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HierarchyLevelItem {

    @NotNull(message = "level is required")
    @Min(value = 1, message = "level must be between 1 and 10")
    @Max(value = 10, message = "level must be between 1 and 10")
    private Integer level;

    @NotBlank(message = "name is required")
    private String name;

    private String description;
}
