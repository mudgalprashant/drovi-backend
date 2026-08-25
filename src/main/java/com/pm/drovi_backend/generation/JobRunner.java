package com.pm.drovi_backend.generation;

import com.pm.drovi_backend.ai.AiCappedException;
import com.pm.drovi_backend.ai.AiProviderException;
import com.pm.drovi_backend.ai.AiCallStatus;
import com.pm.drovi_backend.ai.AiUnavailableException;
import com.pm.drovi_backend.common.Correlation;
import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.config.AppConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims one generation job at a time and runs it.
 *
 * <h2>One job at a time, on purpose</h2>
 *
 * The provider's free tier allows 15 requests per minute, and a single generation is many
 * sequential model calls — research, then spec, then seed. The limit we will hit is the
 * per-minute one, not the daily one, so the runner <strong>paces itself rather than fanning
 * out</strong>: one claim per tick, one job in flight, no worker pool. Adding concurrency
 * here does not make generation faster; it makes it 429.
 *
 * <p>That also means throughput is deliberately low. It is not a bug to be tuned away without
 * first checking what the provider tier actually allows.
 *
 * <h2>No transaction spans a model call</h2>
 *
 * Claim in one short transaction, run with none open, record the outcome in another. A job
 * takes minutes and the pool is five connections; {@code AiGateway} enforces the middle step
 * by throwing, and this class is arranged so that never comes up.
 *
 * <h2>What happens when this process dies mid-job</h2>
 *
 * {@code ops/StuckJobSweeper} reclaims it after {@code ai.job.timeout.seconds}. It asks
 * {@link #currentJobId()} first, so a slow job is never taken from a runner that is still on it.
 */
@Component
@Slf4j
public class JobRunner {

    private static final int MAX_ATTEMPTS_DEFAULT = 3;
    private static final int BACKOFF_DEFAULT_SECONDS = 60;
    private static final boolean ENABLED_BY_DEFAULT = false;

    private final JobStore jobs;
    private final AppConfigService config;
    private final ObjectMapper mapper;
    private final JobChain chain;
    private final Map<JobKind, JobHandler> handlers = new EnumMap<>(JobKind.class);

    /**
     * When to start claiming again after an outcome no retry can fix. In memory rather than in
     * the database because there is one runner: persisting it would be state to reconcile for
     * a value that is meaningless after a restart anyway.
     */
    private volatile Instant pausedUntil = Instant.EPOCH;

    /**
     * The job this process is working on, or null.
     *
     * <p>Exists for the stuck-job sweeper, which reclaims work from runners that are <em>gone</em>.
     * A slow step is not a stuck one, and without this a long-running job gets requeued underneath
     * its own runner — which then finishes and marks the same row SUCCEEDED, leaving a duplicate
     * queued job that generates over the top of a project that was just built.
     */
    private volatile UUID currentJobId;

    public JobRunner(JobStore jobs, AppConfigService config, ObjectMapper mapper,
                     JobChain chain, List<JobHandler> handlers) {
        this.jobs = jobs;
        this.config = config;
        this.mapper = mapper;
        this.chain = chain;
        for (JobHandler handler : handlers) {
            JobHandler clash = this.handlers.put(handler.kind(), handler);
            if (clash != null) {
                // Two handlers for one kind means whichever Spring listed first silently wins,
                // and the job does something other than what whoever added the second expected.
                throw new IllegalStateException(
                        "Two handlers for %s: %s and %s".formatted(handler.kind(),
                                clash.getClass().getSimpleName(), handler.getClass().getSimpleName()));
            }
        }
        log.info("job.runner.handlers kinds={}", this.handlers.keySet());
    }

    /**
     * The cadence is a Spring property, not an {@code app_config} row, because {@code @Scheduled}
     * resolves its interval once at startup and cannot read a table. The on/off switch <em>is</em>
     * an {@code app_config} row — see {@link #claimAndRunOne()} — so ops can stop the runner
     * without a deploy, which is the half that matters during an incident.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the delay is measured from the end of the
     * previous run, so a job that takes four minutes does not come back to find three ticks
     * queued up behind it.
     */
    @Scheduled(fixedDelayString = "${drovi.jobs.poll-interval-ms:5000}",
            initialDelayString = "${drovi.jobs.initial-delay-ms:15000}")
    void poll() {
        try {
            claimAndRunOne();
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently unscheduled by some executors and
            // merely logged by others. Neither is a thing to find out during an incident, so
            // nothing escapes this method.
            log.error("job.runner.tick.failed", e);
        }
    }

    /**
     * One tick: claim at most one job and run it to a terminal or requeued state.
     *
     * <p>Separate from {@link #poll()} so tests can drive it directly: waiting on a scheduler
     * to prove a state machine makes a slow test that fails on a loaded machine. It is public
     * for the same reason an operator eventually wants a "run one now" after fixing whatever
     * caused a pause.
     *
     * @return true if a job was claimed
     */
    public boolean claimAndRunOne() {
        if (!config.getBoolean("ai.job.runner.enabled", ENABLED_BY_DEFAULT)) {
            return false;
        }
        if (Instant.now().isBefore(pausedUntil)) {
            return false;
        }

        Optional<GenerationJob> claimed = jobs.claimNext(handlers.keySet());
        if (claimed.isEmpty()) {
            return false;
        }
        GenerationJob job = claimed.get();
        currentJobId = job.id();
        try {
            // The job's OWN id, not a fresh one: the string in the logs is the string in
            // generation_job, so a user's failed generation and its log lines are found with the
            // same query. A generation is five or six jobs and a dozen model calls, and
            // reconstructing one used to mean reading timestamps.
            Correlation.as(job.id().toString(), () -> run(job));
        } finally {
            // Cleared in a finally, or a runner that throws its way out leaves the sweeper
            // permanently unable to reclaim the one job it most needs to.
            currentJobId = null;
        }
        return true;
    }

    /** What this process is working on, for the sweeper. Null when idle. */
    public UUID currentJobId() {
        return currentJobId;
    }

    private void run(GenerationJob job) {
        JobHandler handler = handlers.get(job.kind());
        if (handler == null) {
            // Unreachable in practice — the claim filters on the kinds a handler exists for,
            // precisely so an unrunnable kind cannot sit at the head of the queue blocking
            // everything behind it. Kept as a belt: requeued without charging an attempt, and
            // deliberately WITHOUT pausing, since the fault is one kind's and not the
            // platform's.
            log.error("job.claimed.without.handler jobId={} kind={}", job.id(), job.kind());
            jobs.requeueWithoutPenalty(job.id(), "NO_HANDLER",
                    "This kind of generation is not available yet.");
            return;
        }

        log.info("job.started jobId={} kind={} attempt={}", job.id(), job.kind(), job.attempt());
        try {
            Map<String, Object> result = handler.handle(job);
            Map<String, Object> outcome = result == null ? Map.of() : result;
            // Decided before the write and applied inside it, so this job's success and its
            // successors land together or not at all.
            jobs.succeed(job.id(), mapper.writeValueAsString(outcome), chain.after(job, outcome));

        } catch (AiCappedException e) {
            // A control fired. The job is fine; the platform is not currently willing to spend,
            // and it may be again within the hour. Queued, not failed, and not charged.
            pause("spend capped (%s)".formatted(e.getReason()));
            jobs.requeueWithoutPenalty(job.id(), "AI_CAPPED",
                    "Generation is paused right now. This job will resume automatically.");

        } catch (AiUnavailableException e) {
            // Configuration, not the job. The operator detail goes to the log only.
            log.error("job.blocked jobId={} detail={}", job.id(), e.getOperatorDetail());
            pause("generation not configured");
            jobs.requeueWithoutPenalty(job.id(), "AI_UNAVAILABLE",
                    "Generation is not available right now. This job will resume automatically.");

        } catch (AiProviderException e) {
            // REFUSED is the one provider failure that is terminal: asking again spends money
            // to be told no a second time. ERROR and TIMEOUT are worth another attempt.
            if (e.getStatus() == AiCallStatus.REFUSED) {
                fail(job, "MODEL_REFUSED",
                        "The model declined to generate this. Try describing the product differently.");
            } else {
                retryOrFail(job, "PROVIDER_ERROR", "The model call did not complete.");
            }

        } catch (DroviException e) {
            // A deliberate refusal from the console's own services — no such collection, not
            // yours, over your plan's limit. These arrived here as "unexpected", which meant
            // three attempts to discover that a missing collection is still missing, and a
            // job that finally failed with INTERNAL rather than with the reason.
            //
            // Terminal, except INTERNAL: those are the ones thrown when something we do could
            // plausibly succeed on a second go, such as failing to allocate a unique key.
            // DroviException messages are already written to be shown to a caller, so the
            // message carries through rather than being replaced by a generic one.
            if (e.getErrorCode() == ErrorCode.INTERNAL) {
                log.warn("job.internal jobId={} detail={}", job.id(), e.getMessage());
                retryOrFail(job, ErrorCode.INTERNAL.code(), "Something went wrong while generating this.");
            } else {
                log.info("job.refused jobId={} errorCode={}", job.id(), e.getErrorCode().code());
                fail(job, e.getErrorCode().code(), e.getMessage());
            }

        } catch (TerminalJobException e) {
            log.warn("job.terminal jobId={} errorCode={} detail={}", job.id(), e.getErrorCode(), e.getMessage());
            fail(job, e.getErrorCode(), e.getMessage());

        } catch (RetryableJobException e) {
            log.info("job.retryable jobId={} attempt={} detail={}", job.id(), job.attempt(), e.getMessage());
            retryOrFail(job, "RETRY_EXHAUSTED", e.getMessage());

        } catch (RuntimeException e) {
            // An unexpected exception is a bug, and a bug might be transient — a dropped
            // connection looks exactly like this. Retried, but bounded, and logged with the
            // stack trace that the job's own error_message must never carry.
            log.error("job.failed.unexpected jobId={} kind={}", job.id(), job.kind(), e);
            retryOrFail(job, "INTERNAL", "Something went wrong while generating this.");
        }
    }

    /**
     * {@code attempt} was already incremented at claim time, so it counts the attempt that has
     * just finished. Out of attempts is a failure; anything else goes back on the queue.
     */
    private void retryOrFail(GenerationJob job, String errorCode, String message) {
        int maxAttempts = config.getInt("ai.max.attempts", MAX_ATTEMPTS_DEFAULT);
        if (job.attempt() >= maxAttempts) {
            fail(job, errorCode, message);
        } else {
            jobs.requeue(job.id(), errorCode, message);
        }
    }

    /**
     * Every terminal failure goes through here, so the chain hears about all of them. A
     * generation that stops must leave a project that says it failed — a project that simply
     * never becomes ready looks like one that is still working, forever.
     *
     * <p>The chain's own failure must not turn a recorded failure into an unrecorded one, so it
     * is logged and swallowed.
     */
    private void fail(GenerationJob job, String errorCode, String message) {
        jobs.fail(job.id(), errorCode, message);
        try {
            chain.afterFailure(job, errorCode);
        } catch (RuntimeException e) {
            log.error("job.chain.afterFailure.failed jobId={}", job.id(), e);
        }
    }

    /**
     * Stop claiming for a while.
     *
     * <p>Only for states that affect <em>every</em> job equally — spend is capped, or no
     * provider is configured. The next job would hit them identically a second later, so
     * polling through only burns queries and, for a spend cap, produces a stream of CAPPED
     * ledger rows that bury the ones worth reading.
     *
     * <p>Deliberately <strong>not</strong> used for a problem with one job or one kind. A
     * global pause triggered by a single unrunnable job is head-of-line blocking wearing a
     * different hat, and it is why the claim filters by kind instead.
     */
    private void pause(String reason) {
        Duration backoff = Duration.ofSeconds(config.getInt("ai.job.backoff.seconds", BACKOFF_DEFAULT_SECONDS));
        pausedUntil = Instant.now().plus(backoff);
        log.warn("job.runner.paused reason={} until={}", reason, pausedUntil);
    }

    /** For tests and for an operator who has just fixed the thing that caused a pause. */
    public void resume() {
        pausedUntil = Instant.EPOCH;
    }
}
