package com.pm.drovi_backend.ai;

import java.util.UUID;

/**
 * Who and what a model call is being made for.
 *
 * <p>{@code accountId} is the only required field, because it is what the per-account cap
 * is charged against and what the ledger's foreign key needs. The other three are the
 * threads back to the work: a call with no job and no project is a chat turn, and one with
 * all four is a step of a generation.
 *
 * <p>The account id must come from the authenticated principal. Accepting one from a request
 * body would let a caller bill their spend to somebody else's daily cap.
 */
public record AiCallContext(UUID accountId, UUID projectId, UUID jobId, UUID threadId) {

    public AiCallContext {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required — it is what the daily cap is charged against");
        }
    }

    public static AiCallContext forAccount(UUID accountId) {
        return new AiCallContext(accountId, null, null, null);
    }

    public AiCallContext withProject(UUID projectId) {
        return new AiCallContext(accountId, projectId, jobId, threadId);
    }

    public AiCallContext withJob(UUID jobId) {
        return new AiCallContext(accountId, projectId, jobId, threadId);
    }
}
