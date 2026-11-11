package com.hierarchyhub.dto;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ReadChildrenRequestBody {

    private static final int DEFAULT_LIMIT = 50;

    private Integer limit;

    /** Opaque cursor from a previous page's {@code nextCursor}; omit for the first page. Ignored when {@code offset} is given. */
    private String cursor;

    @Min(value = 0, message = "offset must be at least 0")
    private Integer offset;

    /** Whether to compute each child's descendant count; defaults to false. */
    private Boolean countDescendants;

    @Min(value = 1, message = "depth must be at least 1")
    private Integer depth;

    private String query;

    /** Whether to include the level blueprint array (and {@code maxCreatedLevel}/{@code maxAllowedLevel}) in the response; defaults to false. */
    private Boolean levelRequired;

    public int limitOrDefault() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    public boolean countDescendantsOrDefault() {
        return Boolean.TRUE.equals(countDescendants);
    }

    public boolean levelRequiredOrDefault() {
        return Boolean.TRUE.equals(levelRequired);
    }
}
