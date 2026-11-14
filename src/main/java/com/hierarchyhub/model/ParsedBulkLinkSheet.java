package com.hierarchyhub.model;

import com.hierarchyhub.dto.BulkLinkRow;
import com.hierarchyhub.dto.BulkLinkRowFailure;

import java.util.List;

public record ParsedBulkLinkSheet(List<BulkLinkRow> rows, List<BulkLinkRowFailure> rowFailures, int duplicateChildRowsSkipped) {
}
