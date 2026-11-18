package com.hierarchyhub.model;

import java.util.List;

public record PagedSearchResponse<T>(long count, int page, int limit, List<T> items) {
}
