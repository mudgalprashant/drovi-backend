package com.pm.drovi_backend;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.generation.JobKind;
import com.pm.drovi_backend.generation.JobStore;
import com.pm.drovi_backend.ops.RequestLogPurge;
import com.pm.drovi_backend.ops.StuckJobSweeper;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5's two sweepers — the failures that arrive without anyone doing anything wrong.
 *
 * <p>{@code mock_request_log} grows by a row per served call and nothing has ever removed one, so
 * on a 500 MB shared database it is what fills it. And a runner killed mid-job leaves its row
 * {@code RUNNING} forever — which since chaining also leaves its project {@code GENERATING},
 * where <strong>the sandbox does not serve</strong>. An instance dying at the wrong moment takes
 * somebody's sandbox dark permanently, with nothing anywhere to explain it.
 */
@SpringBootTest
class HousekeepingTest extends PostgresTestBase {

    @Autowired
    RequestLogPurge purge;
    @Autowired
    StuckJobSweeper sweeper;
    @Autowired
    JobStore jobs;
    @Autowired
    AppConfigService config;
    @Autowired
    JdbcTemplate jdbc;

    private UUID account;
    private UUID project;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mock_request_log");
        jdbc.update("DELETE FROM generation_job");
        setConfig("purge.enabled", "true");
        setConfig("purge.batch.size", "5000");
        setConfig("purge.max.batches", "20");
        setConfig("sweeper.enabled", "true");
        setConfig("ai.job.timeout.seconds", "300");
        setConfig("ai.max.attempts", "3");
        jdbc.update("UPDATE plan_catalog SET log_retention_days = 7 WHERE code = 'FREE'");

        account = jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
        project = jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, name, source_product, status, auth_mode)
                VALUES (?, 'Cards', 'Stripe', 'GENERATING', 'NONE') RETURNING id
                """, UUID.class, account);
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE plan_catalog SET log_retention_days = 7 WHERE code = 'FREE'");
        setConfig("ai.job.timeout.seconds", "300");
        setConfig("ai.max.attempts", "3");
    }

    // --- the request-log purge ------------------------------------------------

    @Test
    void purge_removesRowsPastThePlansRetentionAndKeepsTheRest() {
        logRow(2);
        logRow(3);
        logRow(30);
        logRow(90);

        assertThat(purge.purge()).isEqualTo(2);
        assertThat(logCount()).isEqualTo(2);
    }

    /** Retention is something a plan sells, so the cutoff is each project's own, not a constant. */
    @Test
    void purge_usesEachProjectsOwnPlanRetention() {
        jdbc.update("UPDATE plan_catalog SET log_retention_days = 60 WHERE code = 'FREE'");
        logRow(30);
        logRow(90);

        purge.purge();

        assertThat(logCount()).as("30 days is inside a 60-day retention").isEqualTo(1);
    }

    @Test
    void purge_withNothingExpired_deletesNothing() {
        logRow(1);
        logRow(2);

        assertThat(purge.purge()).isZero();
        assertThat(logCount()).isEqualTo(2);
    }

    /**
     * Small statements, each committed. A single delete of a few million rows holds locks on the
     * table the inspector reads for as long as it takes.
     */
    @Test
    void purge_worksInBatchesRatherThanOneStatement() {
        setConfig("purge.batch.size", "3");
        for (int i = 0; i < 10; i++) {
            logRow(30);
        }

        assertThat(purge.purge()).isEqualTo(10);
        assertThat(logCount()).isZero();
    }

    /** A run is bounded, not the backlog — a large arrears is cleared over several runs. */
    @Test
    void purge_boundsOneRunAndLeavesTheRestForTheNext() {
        setConfig("purge.batch.size", "2");
        setConfig("purge.max.batches", "2");
        for (int i = 0; i < 10; i++) {
            logRow(30);
        }

        assertThat(purge.purge()).isEqualTo(4);
        assertThat(logCount()).isEqualTo(6);
    }

    /** A missing config row must not silently start deleting a user's inspector history. */
    @Test
    void purge_whenTheEnabledRowIsMissing_doesNothing() {
        logRow(30);
        jdbc.update("DELETE FROM app_config WHERE key = 'purge.enabled'");
        config.refresh();
        try {
            assertThat(purge.purge()).isZero();
            assertThat(logCount()).isEqualTo(1);
        } finally {
            setConfig("purge.enabled", "true");
        }
    }

    // --- the stuck-job sweeper ------------------------------------------------

    /** A killed runner has done nothing wrong, so its job goes back on the queue. */
    @Test
    void sweeper_reclaimsAJobWhoseRunnerDisappeared() {
        UUID job = stuckJob(1, 600);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(errorCodeOf(job)).isEqualTo("RUNNER_LOST");
    }

    /** Failing it would make a deploy during a generation destroy the generation. */
    @Test
    void sweeper_keepsTheAttemptSpentSoAJobThatKillsRunnersCannotLoopForever() {
        UUID job = stuckJob(1, 600);

        sweeper.sweep();

        assertThat(attemptOf(job)).isEqualTo(1);
    }

    @Test
    void sweeper_whenAttemptsAreExhausted_failsTheJob() {
        UUID job = stuckJob(3, 600);

        sweeper.sweep();

        assertThat(statusOf(job)).isEqualTo("FAILED");
    }

    /**
     * The failure worth fixing. A stranded job leaves its project GENERATING, and a GENERATING
     * project does not serve — so the sandbox is dark until someone notices by hand.
     */
    @Test
    void sweeper_whenAJobIsFinallyLost_takesTheProjectOutOfGenerating() {
        stuckJob(3, 600);

        sweeper.sweep();

        assertThat(projectStatus()).as("a project stuck in GENERATING is a sandbox that never answers")
                .isEqualTo("FAILED");
    }

    /** Long is not the same as lost. */
    @Test
    void sweeper_leavesAJobThatHasNotTimedOutAlone() {
        UUID job = stuckJob(1, 10);

        assertThat(sweeper.sweep()).isZero();
        assertThat(statusOf(job)).isEqualTo("RUNNING");
    }

    @Test
    void sweeper_leavesQueuedAndFinishedJobsAlone() {
        UUID queued = jobs.enqueue(account, project, null, JobKind.RESEARCH, "x").id();
        UUID done = stuckJob(1, 600);
        jdbc.update("UPDATE generation_job SET status = 'SUCCEEDED' WHERE id = ?", done);

        assertThat(sweeper.sweep()).isZero();
        assertThat(statusOf(queued)).isEqualTo("QUEUED");
        assertThat(statusOf(done)).isEqualTo("SUCCEEDED");
    }

    @Test
    void sweeper_whenDisabled_doesNothing() {
        UUID job = stuckJob(1, 600);
        setConfig("sweeper.enabled", "false");

        assertThat(sweeper.sweep()).isZero();
        assertThat(statusOf(job)).isEqualTo("RUNNING");
    }

    @Test
    void sweeper_whenTheEnabledRowIsMissing_doesNothing() {
        UUID job = stuckJob(1, 600);
        jdbc.update("DELETE FROM app_config WHERE key = 'sweeper.enabled'");
        config.refresh();
        try {
            assertThat(sweeper.sweep()).isZero();
            assertThat(statusOf(job)).isEqualTo("RUNNING");
        } finally {
            setConfig("sweeper.enabled", "true");
        }
    }

    // --- fixtures -------------------------------------------------------------

    private void logRow(int daysAgo) {
        jdbc.update("""
                INSERT INTO mock_request_log
                    (project_id, method, path, status_code, latency_ms, created_at)
                VALUES (?, 'GET', '/v1/cards', 200, 5, now() - make_interval(days => ?))
                """, project, daysAgo);
    }

    private UUID stuckJob(int attempt, int startedSecondsAgo) {
        UUID job = jobs.enqueue(account, project, null, JobKind.RESEARCH, "mimic something").id();
        jdbc.update("""
                UPDATE generation_job
                   SET status = 'RUNNING', attempt = ?,
                       started_at = now() - make_interval(secs => ?)
                 WHERE id = ?
                """, attempt, startedSecondsAgo, job);
        return job;
    }

    private long logCount() {
        return jdbc.queryForObject("SELECT count(*) FROM mock_request_log WHERE project_id = ?",
                Long.class, project);
    }

    private String statusOf(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private String errorCodeOf(UUID jobId) {
        return jdbc.queryForObject("SELECT error_code FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private int attemptOf(UUID jobId) {
        return jdbc.queryForObject("SELECT attempt FROM generation_job WHERE id = ?", Integer.class, jobId);
    }

    private String projectStatus() {
        return jdbc.queryForObject("SELECT status FROM sandbox_project WHERE id = ?", String.class, project);
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by HousekeepingTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
