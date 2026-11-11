package com.hierarchyhub.dto;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public final class BulkLinkJob {

    /** At most this many row failures are kept (for the completion log); {@link #failedRows} still counts every one of them. */
    public static final int MAX_FAILURES_KEPT = 200;

    private final String jobId;
    private final String fileName;
    private final String createdBy;
    private final String dateCreatedOn;
    private final String rootOrgId;
    private final int totalRows;
    private final int duplicateChildRowsSkipped;

    private int succeededRows;
    private int failedRows;
    private final List<BulkLinkRowFailure> failures = new ArrayList<>();

    public BulkLinkJob(String jobId, String fileName, String createdBy, String dateCreatedOn, String rootOrgId,
                        int totalRows, int duplicateChildRowsSkipped) {
        this.jobId = jobId;
        this.fileName = fileName;
        this.createdBy = createdBy;
        this.dateCreatedOn = dateCreatedOn;
        this.rootOrgId = rootOrgId;
        this.totalRows = totalRows;
        this.duplicateChildRowsSkipped = duplicateChildRowsSkipped;
    }

    public void recordSuccess() {
        succeededRows++;
    }

    /** Records one more failed row, keeping only the first {@link #MAX_FAILURES_KEPT} in detail. */
    public void recordFailure(BulkLinkRowFailure failure) {
        failedRows++;
        if (failures.size() < MAX_FAILURES_KEPT) {
            failures.add(failure);
        }
    }

}
