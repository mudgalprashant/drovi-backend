package com.pm.drovi_backend.generation;

import java.util.Map;

/**
 * What a job of one kind actually does. Phase 3.3 supplies the implementations.
 *
 * <p>A handler is called with <strong>no transaction open</strong> and must keep it that way
 * across any model call — {@code AiGateway} throws otherwise. Short transactions inside the
 * handler, around the writes between calls, are the intended shape.
 *
 * <p>A handler does not touch {@code generation_job}: it returns a result or throws, and
 * {@link JobRunner} owns every status transition. Two writers to a job's status is how a job
 * ends up SUCCEEDED with an error message attached.
 *
 * <p>Throw {@link RetryableJobException} for anything another attempt could fix — unparseable
 * model output above all, which is a retry and not a failure. Throw
 * {@link TerminalJobException} when a retry would only spend money to reach the same place.
 */
public interface JobHandler {

    JobKind kind();

    /**
     * @return the job's {@code result}, serialised to {@code jsonb}. An empty map is fine; the
     *         column is nullable and a handler with nothing to report should say so with one.
     */
    Map<String, Object> handle(GenerationJob job);
}
