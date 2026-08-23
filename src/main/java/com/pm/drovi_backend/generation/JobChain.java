package com.pm.drovi_backend.generation;

import java.util.List;
import java.util.Map;

/**
 * What happens after a job ends. The only thing {@link JobRunner} knows about pipelines.
 *
 * <p>The runner stays generic on purpose — it claims, retries and records, and it would do all
 * of that identically for a pipeline of a different shape. Which step follows which lives in
 * one class in {@code generation.pipeline}, so changing the order of generation does not mean
 * editing the machinery that runs it.
 *
 * <p>{@link #after} must be a <em>calculation</em>: it decides what comes next and returns it,
 * without enqueueing anything itself. The runner then writes the finished job and its
 * successors in one transaction, so a chain can never contain a successor whose predecessor
 * was not recorded as succeeded.
 */
public interface JobChain {

    /** @return the jobs to enqueue, or empty when this was the last step */
    List<NewJob> after(GenerationJob job, Map<String, Object> result);

    /** A job that reached a terminal failure. Nothing follows it; this is for cleaning up. */
    void afterFailure(GenerationJob job, String errorCode);

    /**
     * A runner with no pipeline: every job stands alone.
     *
     * <p>The honest default for a {@link JobRunner} driving one kind of work, and what a test
     * of the runner's own machinery wants — chaining is a separate behaviour and deserves to
     * fail separately.
     */
    static JobChain none() {
        return new JobChain() {
            @Override
            public List<NewJob> after(GenerationJob job, Map<String, Object> result) {
                return List.of();
            }

            @Override
            public void afterFailure(GenerationJob job, String errorCode) {
                // nothing follows, so nothing to unwind
            }
        };
    }
}
