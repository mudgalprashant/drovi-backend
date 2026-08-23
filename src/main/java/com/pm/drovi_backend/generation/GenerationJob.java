package com.pm.drovi_backend.generation;

import com.pm.drovi_backend.ai.AiCallContext;

import java.util.Map;
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
 * @param input   this kind of job's structured parameters. <strong>Untrusted</strong>: a
 *                user's pasted documentation lands here verbatim, and it is data rather than
 *                instructions no matter how imperatively it is worded
 */
public record GenerationJob(UUID id,
                            UUID accountId,
                            UUID projectId,
                            UUID threadId,
                            JobKind kind,
                            JobStatus status,
                            String prompt,
                            int attempt,
                            Map<String, Object> input) {

    public GenerationJob {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    /**
     * The context every model call this job makes must carry, so spend lands on the right
     * account and the ledger can be read back as "what did this job cost".
     */
    public AiCallContext callContext() {
        return new AiCallContext(accountId, projectId, id, threadId);
    }
}
