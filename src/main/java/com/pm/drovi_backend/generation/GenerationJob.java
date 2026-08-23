package com.pm.drovi_backend.generation;

import com.pm.drovi_backend.ai.AiCallContext;

import java.util.UUID;

/**
 * One row of {@code generation_job}, as the runner sees it.
 *
 * <p>A record rather than an entity, deliberately. The row is claimed by a conditional
 * {@code UPDATE} and written back in short bursts between long model calls; a JPA entity
 * would want a session open across the whole job, which is exactly what must not happen.
 *
 * @param attempt how many times this job has been claimed, <em>including the current
 *                claim</em> — the counter is incremented as part of claiming, so a job whose
 *                runner is killed mid-flight has already paid for that attempt
 */
public record GenerationJob(UUID id,
                            UUID accountId,
                            UUID projectId,
                            UUID threadId,
                            JobKind kind,
                            JobStatus status,
                            String prompt,
                            int attempt) {

    /**
     * The context every model call this job makes must carry, so spend lands on the right
     * account and the ledger can be read back as "what did this job cost".
     */
    public AiCallContext callContext() {
        return new AiCallContext(accountId, projectId, id, threadId);
    }
}
