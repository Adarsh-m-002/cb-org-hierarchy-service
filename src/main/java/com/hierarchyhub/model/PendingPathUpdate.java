package com.hierarchyhub.model;

/** One org and its freshly computed {@code parentPathId}, awaiting the diff-check/dispatch step. */
public record PendingPathUpdate(String orgId, String parentPathId) {
}
