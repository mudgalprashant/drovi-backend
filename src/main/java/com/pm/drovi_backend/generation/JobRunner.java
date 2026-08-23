package com.pm.drovi_backend.generation;

import com.pm.drovi_backend.ai.AiCappedException;
import com.pm.drovi_backend.ai.AiProviderException;
import com.pm.drovi_backend.ai.AiCallStatus;
import com.pm.drovi_backend.ai.AiUnavailableException;
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
 * <h2>What is not here</h2>
 *
 * A sweeper for jobs left {@code RUNNING} by a crashed instance. That is Phase 5, and until
 * it exists {@code ai.job.timeout.seconds} is decorative — a killed runner leaves its job
 * RUNNING and nothing reclaims it.
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
    private final Map<JobKind, JobHandler> handlers = new EnumMap<>(JobKind.class);

    /**
     * When to start claiming again after an outcome no retry can fix. In memory rather than in
     * the database because there is one runner: persisting it would be state to reconcile for
     * a value that is meaningless after a restart anyway.
     */
    private volatile Instant pausedUntil = Instant.EPOCH;

    public JobRunner(JobStore jobs, AppConfigService config, ObjectMapper mapper, List<JobHandler> handlers) {
        this.jobs = jobs;
        this.config = config;
        this.mapper = mapper;
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
        run(claimed.get());
        return true;
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
            jobs.succeed(job.id(), mapper.writeValueAsString(result == null ? Map.of() : result));

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
                jobs.fail(job.id(), "MODEL_REFUSED",
                        "The model declined to generate this. Try describing the product differently.");
            } else {
                retryOrFail(job, "PROVIDER_ERROR", "The model call did not complete.");
            }

        } catch (TerminalJobException e) {
            log.warn("job.terminal jobId={} errorCode={} detail={}", job.id(), e.getErrorCode(), e.getMessage());
            jobs.fail(job.id(), e.getErrorCode(), e.getMessage());

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
            jobs.fail(job.id(), errorCode, message);
        } else {
            jobs.requeue(job.id(), errorCode, message);
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
