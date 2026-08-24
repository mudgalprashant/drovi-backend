package com.pm.drovi_backend.generation;

import java.util.Map;

/**
 * A job to enqueue, described but not yet written.
 *
 * <p>It exists so that {@link JobChain} can decide what comes next as a pure calculation, and
 * {@link JobStore} can then write the finished job and its successors <em>in one
 * transaction</em>. Deciding and writing in one step would mean a successor enqueued against a
 * job that then failed to be marked SUCCEEDED.
 */
public record NewJob(JobKind kind, String prompt, Map<String, Object> input) {

    public NewJob {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
