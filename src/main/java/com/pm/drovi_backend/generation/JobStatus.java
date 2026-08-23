package com.pm.drovi_backend.generation;

/**
 * Where a job is. Mirrors the {@code CHECK} constraint on {@code generation_job.status}.
 *
 * <p>{@link #QUEUED} is the resting state for work that has not been given up on — including
 * work whose last attempt was refused by a spend cap. A job waiting for the kill switch to
 * come back on is queued, not failed, because nothing about it is wrong.
 */
public enum JobStatus {

    QUEUED,
    /** Claimed by a runner. Nothing else may take it. */
    RUNNING,
    SUCCEEDED,
    /** Terminal. Either out of attempts, or a failure no retry would fix. */
    FAILED,
    /** Terminal, by request rather than by failure. */
    CANCELLED
}
