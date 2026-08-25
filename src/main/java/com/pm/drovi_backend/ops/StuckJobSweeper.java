package com.pm.drovi_backend.ops;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.JobChain;
import com.pm.drovi_backend.generation.JobRunner;
import com.pm.drovi_backend.generation.JobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Reclaims generation jobs left {@code RUNNING} by a runner that is no longer there.
 *
 * <p>A deploy, an out-of-memory kill, Render recycling a free instance — any of them can end a
 * process mid-job. The row stays {@code RUNNING} and nothing ever looks at it again, which made
 * {@code ai.job.timeout.seconds} a decorative column for three phases.
 *
 * <p>Since chaining landed it stopped being merely untidy. A stranded job leaves its project
 * {@code GENERATING}, and <strong>a GENERATING project does not serve</strong> — so an instance
 * dying at the wrong moment takes somebody's sandbox offline permanently, with no error anywhere
 * to explain it.
 *
 * <h2>Reclaimed, not failed</h2>
 *
 * A job whose runner was killed has done nothing wrong, so if it has attempts left it goes back
 * on the queue. Failing it would mean a deploy during a generation destroys the generation, which
 * would make deploying something to schedule around.
 *
 * <p>The attempt it was on is <em>kept spent</em>. That is deliberate and is the same reasoning
 * as incrementing at claim time: a job that reliably kills its runner must run out of attempts,
 * or it takes the instance down forever.
 *
 * <h2>Why it asks the runner first</h2>
 *
 * A job this process is actively working on is not stuck, however long it has been going. Without
 * the check, a slow step gets requeued underneath its own runner, which then finishes and marks
 * the same row SUCCEEDED — leaving a duplicate queued job that generates over the top of a
 * project that was just built.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StuckJobSweeper {

    private static final boolean ENABLED_BY_DEFAULT = false;
    private static final int TIMEOUT_DEFAULT_SECONDS = 300;
    private static final int MAX_ATTEMPTS_DEFAULT = 3;
    /** One run reclaims a bounded number, so a pathological backlog cannot become one long run. */
    private static final int MAX_PER_RUN = 50;

    private final JdbcTemplate jdbc;
    private final JobStore jobs;
    private final JobRunner runner;
    private final JobChain chain;
    private final AppConfigService config;

    @Scheduled(fixedDelayString = "${drovi.ops.sweeper-interval-ms:60000}",
            initialDelayString = "${drovi.ops.sweeper-initial-delay-ms:90000}")
    void scheduled() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("sweeper.failed", e);
        }
    }

    /**
     * Separate from the schedule so a test can drive it, and public so an operator who has just
     * restarted the service can reclaim its abandoned work immediately rather than waiting out
     * the timeout.
     *
     * @return how many jobs were reclaimed
     */
    public int sweep() {
        if (!config.getBoolean("sweeper.enabled", ENABLED_BY_DEFAULT)) {
            return 0;
        }
        int timeoutSeconds = config.getInt("ai.job.timeout.seconds", TIMEOUT_DEFAULT_SECONDS);
        int maxAttempts = config.getInt("ai.max.attempts", MAX_ATTEMPTS_DEFAULT);
        UUID inFlight = runner.currentJobId();

        int reclaimed = 0;
        for (UUID jobId : stuck(timeoutSeconds)) {
            if (jobId.equals(inFlight)) {
                // Slow, not stuck. Taking it would leave two runners on one job.
                continue;
            }
            reclaim(jobId, maxAttempts);
            reclaimed++;
        }
        if (reclaimed > 0) {
            log.warn("sweeper.reclaimed count={} timeoutSeconds={}", reclaimed, timeoutSeconds);
        }
        return reclaimed;
    }

    private List<UUID> stuck(int timeoutSeconds) {
        return jdbc.queryForList("""
                SELECT id FROM generation_job
                 WHERE status = 'RUNNING'
                   AND started_at < now() - make_interval(secs => ?)
                 ORDER BY started_at
                 LIMIT ?
                """, UUID.class, timeoutSeconds, MAX_PER_RUN);
    }

    private void reclaim(UUID jobId, int maxAttempts) {
        GenerationJob job = jobs.find(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (job.attempt() < maxAttempts) {
            jobs.requeue(jobId, "RUNNER_LOST",
                    "This step was interrupted and will be tried again.");
            log.warn("sweeper.requeued jobId={} kind={} attempt={}", jobId, job.kind(), job.attempt());
            return;
        }

        jobs.fail(jobId, "RUNNER_LOST", "This step was interrupted and could not be finished.");
        try {
            // The chain is what moves the project out of GENERATING. Without this the row is
            // tidy and the sandbox is still dark, which is the failure worth fixing.
            chain.afterFailure(job, "RUNNER_LOST");
        } catch (RuntimeException e) {
            log.error("sweeper.chain.failed jobId={}", jobId, e);
        }
        log.warn("sweeper.failed.exhausted jobId={} kind={} attempts={}", jobId, job.kind(), job.attempt());
    }
}
