package com.hierarchyhub.model;

import java.util.List;

public record PageWithCount<T>(List<T> rows, long totalCount) {
}
