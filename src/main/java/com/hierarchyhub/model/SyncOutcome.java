package com.hierarchyhub.model;

/** Counts from one parentPathId sync pass (or one chunk of it) - immutable so chunk-level outcomes are combined by addition, never mutated in place. */
public record SyncOutcome(int updated, int skipped, int failed) {

    public static final SyncOutcome ZERO = new SyncOutcome(0, 0, 0);

    public SyncOutcome plus(SyncOutcome other) {
        return new SyncOutcome(updated + other.updated, skipped + other.skipped, failed + other.failed);
    }
}
