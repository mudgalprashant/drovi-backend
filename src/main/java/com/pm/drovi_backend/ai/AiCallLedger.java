package com.pm.drovi_backend.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Invariant 3, at its single write point: one {@code ai_call} row per model call, whether
 * the call succeeded, failed, timed out, or never left the building.
 *
 * <p>{@code REQUIRES_NEW} is the important part. A generation step that writes a ledger row
 * and then fails must not take the ledger row down with it — a failed call that consumed
 * input tokens still cost money, and a ledger that rolls back with the work under-reports
 * exactly when spend is running away.
 *
 * <p>There is deliberately no {@code AiCall} entity. Nothing reads a single row by id; the
 * table is written once per call and read in aggregate by the caps. An entity would buy
 * dirty checking nobody wants over the hottest write path generation has.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiCallLedger {

    private final JdbcTemplate jdbc;

    /**
     * @param costMicros priced at the rate in force at call time, never recomputed later
     * @return the ledger row's id, so a caller can correlate a failure to its charge
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(AiCallContext context, String providerCode, String model, AiPurpose purpose,
                       AiCallStatus status, int inputTokens, int outputTokens, long costMicros,
                       Integer latencyMs) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO ai_call (account_id, project_id, job_id, thread_id,
                                     provider_code, model, purpose,
                                     input_tokens, output_tokens, cost_micros,
                                     status, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                context.accountId(), context.projectId(), context.jobId(), context.threadId(),
                providerCode, model, purpose.name(),
                inputTokens, outputTokens, costMicros,
                status.name(), latencyMs);

        log.info("ai.call accountId={} purpose={} model={} status={} tokens={}/{} costMicros={} latencyMs={}",
                context.accountId(), purpose, model, status, inputTokens, outputTokens, costMicros, latencyMs);
        return id;
    }

    /**
     * Spend so far today across the whole platform.
     *
     * <p>The day boundary is UTC and is computed in SQL, not in Java. {@code AT TIME ZONE}
     * appears twice on purpose: the first converts {@code now()} into UTC wall-clock time so
     * the truncation happens at the right instant, the second converts the truncated value
     * back to an absolute time so it compares correctly against {@code timestamptz}. Drop
     * the second and the comparison silently reinterprets the boundary in the session's
     * timezone — which on a server set to anything but UTC moves the cap's reset by hours.
     */
    @Transactional(readOnly = true)
    public long spentTodayMicros() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(cost_micros), 0)
                  FROM ai_call
                 WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """, Long.class);
    }

    /** The same UTC day, for one account. */
    @Transactional(readOnly = true)
    public long spentTodayMicros(UUID accountId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(cost_micros), 0)
                  FROM ai_call
                 WHERE account_id = ?
                   AND created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """, Long.class, accountId);
    }
}
