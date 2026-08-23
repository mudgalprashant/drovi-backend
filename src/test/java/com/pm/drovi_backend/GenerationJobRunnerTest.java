package com.pm.drovi_backend;

import com.pm.drovi_backend.ai.AiCallStatus;
import com.pm.drovi_backend.ai.AiCappedException;
import com.pm.drovi_backend.ai.AiProviderException;
import com.pm.drovi_backend.ai.AiUnavailableException;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.JobHandler;
import com.pm.drovi_backend.generation.JobKind;
import com.pm.drovi_backend.generation.JobRunner;
import com.pm.drovi_backend.generation.JobStore;
import com.pm.drovi_backend.generation.RetryableJobException;
import com.pm.drovi_backend.generation.TerminalJobException;
import com.pm.drovi_backend.support.PostgresTestBase;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.2 — the job runner's state machine.
 *
 * <p>Driven by calling {@code claimAndRunOne()} directly rather than by waiting on the
 * scheduler. A state machine proved by sleeping is a slow test that fails on a loaded
 * machine; that the schedule itself fires is a separate question, and
 * {@link JobSchedulingTest} is the one test that asks it.
 *
 * <p>The handler under test is a stub whose behaviour each test sets, which is how the
 * runner's reaction to a capped call, a refusal or unparseable output can be asserted
 * without an API key, a network, or a pipeline that does not exist yet.
 */
@SpringBootTest
class GenerationJobRunnerTest extends PostgresTestBase {

    /** Stands in for Phase 3.3's real handlers. RESEARCH because it is the pipeline's first step. */
    static class StubHandler implements JobHandler {

        final AtomicInteger calls = new AtomicInteger();
        Function<GenerationJob, Map<String, Object>> behaviour = job -> Map.of("ok", true);
        volatile GenerationJob lastJob;

        @Override
        public JobKind kind() {
            return JobKind.RESEARCH;
        }

        @Override
        public Map<String, Object> handle(GenerationJob job) {
            calls.incrementAndGet();
            lastJob = job;
            return behaviour.apply(job);
        }
    }

    @Autowired
    JobStore jobs;
    @Autowired
    AppConfigService config;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * Built here rather than injected. {@link JobRunner} is a plain class over real
     * infrastructure beans, so constructing one with exactly the handlers a test wants is both
     * simpler and durable: the real handlers land one per slice, and an injected runner would
     * collide with this stub the moment one of them claimed the same kind.
     */
    private JobRunner runner;
    private final StubHandler handler = new StubHandler();

    private UUID account;

    @BeforeEach
    void resetTheQueue() {
        runner = new JobRunner(jobs, config, mapper, List.of(handler));
        jdbc.update("DELETE FROM generation_job");
        setConfig("ai.job.runner.enabled", "true");
        setConfig("ai.max.attempts", "3");
        setConfig("ai.job.backoff.seconds", "60");
        runner.resume();
        handler.calls.set(0);
        handler.lastJob = null;
        handler.behaviour = job -> Map.of("ok", true);
        account = newAccount();
    }

    @AfterEach
    void restoreConfig() {
        setConfig("ai.job.runner.enabled", "true");
        setConfig("ai.max.attempts", "3");
        setConfig("ai.job.backoff.seconds", "60");
        runner.resume();
    }

    // --- the happy path -------------------------------------------------------

    @Test
    void claimAndRunOne_whenTheHandlerSucceeds_marksTheJobSucceededAndStoresItsResult() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> Map.of("endpoints", 12, "product", "Stripe");

        assertThat(runner.claimAndRunOne()).isTrue();

        assertThat(statusOf(job)).isEqualTo("SUCCEEDED");
        assertThat(resultOf(job)).contains("\"endpoints\"").contains("12");
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM generation_job WHERE id = ?",
                Boolean.class, job)).isTrue();
    }

    /** The job the handler receives must be the row, not a half-populated shell. */
    @Test
    void claimAndRunOne_handsTheHandlerTheJobsOwnPromptAndOwner() {
        GenerationJob enqueued = jobs.enqueue(account, null, null, JobKind.RESEARCH, "mimic Stripe's card API");

        runner.claimAndRunOne();

        assertThat(handler.calls).hasValue(1);
        assertThat(handler.lastJob.prompt()).isEqualTo("mimic Stripe's card API");
        assertThat(handler.lastJob.accountId()).isEqualTo(account);
        assertThat(handler.lastJob.id()).isEqualTo(enqueued.id());
        // The call context is what puts this job's spend on this job's account in the ledger.
        assertThat(handler.lastJob.callContext().accountId()).isEqualTo(account);
        assertThat(handler.lastJob.callContext().jobId()).isEqualTo(enqueued.id());
    }

    @Test
    void claimAndRunOne_withAnEmptyQueue_doesNothingAndSaysSo() {
        assertThat(runner.claimAndRunOne()).isFalse();
        assertThat(handler.calls).hasValue(0);
    }

    /** One claim per tick. The provider's per-minute limit is the reason, not modesty. */
    @Test
    void claimAndRunOne_withSeveralQueued_takesExactlyOne() {
        enqueue(JobKind.RESEARCH);
        enqueue(JobKind.RESEARCH);
        enqueue(JobKind.RESEARCH);

        runner.claimAndRunOne();

        assertThat(handler.calls).hasValue(1);
        assertThat(countByStatus("QUEUED")).isEqualTo(2);
    }

    /** Oldest first, or a busy queue starves whoever arrived while it was busy. */
    @Test
    void claimAndRunOne_takesTheOldestQueuedJobFirst() {
        UUID first = enqueue(JobKind.RESEARCH).id();
        jdbc.update("UPDATE generation_job SET created_at = now() - interval '1 hour' WHERE id = ?", first);
        enqueue(JobKind.RESEARCH);

        runner.claimAndRunOne();

        assertThat(statusOf(first)).isEqualTo("SUCCEEDED");
    }

    // --- claiming -------------------------------------------------------------

    /**
     * The claim is what stops two runners doing the same generation twice — once in money and
     * once in whatever it writes. There is one runner today, but "there is one instance" is a
     * deployment fact, not a guarantee, and it stops being true the first time somebody scales
     * to two or a deploy overlaps.
     */
    @Test
    void claimNext_neverHandsTheSameJobToTwoCallers() {
        enqueue(JobKind.RESEARCH);

        var first = jobs.claimNext(Set.of(JobKind.RESEARCH));
        var second = jobs.claimNext(Set.of(JobKind.RESEARCH));

        assertThat(first).isPresent();
        assertThat(second).as("the second claim must find nothing, not the same row").isEmpty();
    }

    /** Claiming costs an attempt, so a runner killed mid-job cannot retry forever. */
    @Test
    void claimNext_countsTheAttemptAtClaimTimeNotAtFailureTime() {
        enqueue(JobKind.RESEARCH);

        assertThat(jobs.claimNext(Set.of(JobKind.RESEARCH)).orElseThrow().attempt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM generation_job LIMIT 1", String.class))
                .isEqualTo("RUNNING");
    }

    // --- retries --------------------------------------------------------------

    /** The plan is explicit: unparseable model output is a retry, not a failure. */
    @Test
    void claimAndRunOne_whenTheOutputIsUnparseable_requeuesRatherThanFailing() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new RetryableJobException("the model's JSON did not parse");
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(attemptOf(job)).isEqualTo(1);
        // finished_at must stay null, or every "how long do jobs take" query is wrong.
        assertThat(jdbc.queryForObject("SELECT finished_at IS NULL FROM generation_job WHERE id = ?",
                Boolean.class, job)).isTrue();
    }

    @Test
    void claimAndRunOne_whenRetriesRunOut_failsTheJob() {
        setConfig("ai.max.attempts", "2");
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new RetryableJobException("still unparseable");
        };

        runner.claimAndRunOne();
        assertThat(statusOf(job)).isEqualTo("QUEUED");

        runner.claimAndRunOne();
        assertThat(statusOf(job)).isEqualTo("FAILED");
        assertThat(attemptOf(job)).isEqualTo(2);
        assertThat(handler.calls).hasValue(2);
    }

    /**
     * An unexpected exception is a bug, but a dropped connection looks identical — so it is
     * retried, bounded, and the message the job carries says nothing about the stack trace.
     */
    @Test
    void claimAndRunOne_whenTheHandlerThrowsSomethingUnexpected_retriesAndLeaksNothing() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new IllegalStateException("NullPointerException at com.pm.internal.Secret:42");
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(errorMessageOf(job))
                .doesNotContain("NullPointerException")
                .doesNotContain("com.pm.internal");
    }

    // --- terminal failures ----------------------------------------------------

    /** Retrying a refusal spends money to be told no a second time. */
    @Test
    void claimAndRunOne_whenTheModelRefuses_failsWithoutSpendingTheRemainingAttempts() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new AiProviderException(AiCallStatus.REFUSED, "declined");
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("FAILED");
        assertThat(errorCodeOf(job)).isEqualTo("MODEL_REFUSED");
        assertThat(attemptOf(job)).isEqualTo(1);
    }

    /** A provider ERROR is the opposite case, and the distinction is the whole point. */
    @Test
    void claimAndRunOne_whenTheProviderErrors_retriesUnlikeARefusal() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new AiProviderException(AiCallStatus.ERROR, "upstream 503");
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
    }

    @Test
    void claimAndRunOne_whenTheHandlerSaysTerminal_failsWithTheHandlersCode() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new TerminalJobException("SPEC_INVALID", "That product's API could not be described.");
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("FAILED");
        assertThat(errorCodeOf(job)).isEqualTo("SPEC_INVALID");
        assertThat(attemptOf(job)).isEqualTo(1);
    }

    // --- the outcomes that are nobody's fault ---------------------------------

    /**
     * A weekend with the kill switch off must not quietly exhaust every queued job's retries
     * and leave them FAILED on Monday for a reason that had nothing to do with them.
     */
    @Test
    void claimAndRunOne_whenSpendIsCapped_requeuesWithoutChargingAnAttempt() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new AiCappedException(AiCappedException.Reason.KILL_SWITCH);
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(attemptOf(job)).as("a capped attempt is not the job's fault").isZero();
        assertThat(errorCodeOf(job)).isEqualTo("AI_CAPPED");
    }

    @Test
    void claimAndRunOne_whenGenerationIsNotConfigured_requeuesAndTellsTheUserNothingAboutIt() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        handler.behaviour = j -> {
            throw new AiUnavailableException("Provider GEMINI needs DROVI_GEMINI_API_KEY and it is not set.");
        };

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(attemptOf(job)).isZero();
        assertThat(errorMessageOf(job)).doesNotContain("DROVI_GEMINI_API_KEY");
    }

    /**
     * Phase 3.3 supplies the handlers. Until then a queued SPEC job must wait, not fail — so
     * that work enqueued today is still runnable the day the handler lands.
     */
    @Test
    void claimAndRunOne_forAKindWithNoHandler_neverClaimsItAtAll() {
        UUID job = enqueue(JobKind.SPEC).id();

        assertThat(runner.claimAndRunOne()).isFalse();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(attemptOf(job)).isZero();
    }

    /**
     * The bug this shape exists to prevent, and it is not hypothetical — the full suite found
     * it. An unrunnable job at the head of the queue must not stop everything behind it: it
     * would be re-claimed every tick forever, or trigger a global pause, and either way a
     * RESEARCH job that works perfectly well would never run.
     */
    @Test
    void claimAndRunOne_withAnUnrunnableJobAtTheHeadOfTheQueue_stillRunsTheOneBehindIt() {
        UUID blocked = enqueue(JobKind.SPEC).id();
        jdbc.update("UPDATE generation_job SET created_at = now() - interval '1 hour' WHERE id = ?", blocked);
        UUID runnable = enqueue(JobKind.RESEARCH).id();

        assertThat(runner.claimAndRunOne()).isTrue();

        assertThat(statusOf(runnable)).isEqualTo("SUCCEEDED");
        assertThat(statusOf(blocked)).as("still waiting for its handler, harming nothing").isEqualTo("QUEUED");
    }

    /**
     * Each of those states would greet the next job identically a second later. Polling
     * through a spend cap in particular produces a stream of CAPPED ledger rows that bury the
     * ones worth reading.
     */
    @Test
    void claimAndRunOne_afterACappedJob_stopsClaimingUntilTheBackoffExpires() {
        enqueue(JobKind.RESEARCH);
        enqueue(JobKind.RESEARCH);
        handler.behaviour = j -> {
            throw new AiCappedException(AiCappedException.Reason.PLATFORM_DAILY_CAP);
        };

        assertThat(runner.claimAndRunOne()).isTrue();
        assertThat(runner.claimAndRunOne()).as("still backed off").isFalse();
        assertThat(handler.calls).hasValue(1);

        runner.resume();
        assertThat(runner.claimAndRunOne()).isTrue();
    }

    // --- the operator's switch ------------------------------------------------

    /**
     * Turning the runner off must leave work waiting, not destroy it — that is what makes it
     * safe to reach for during an incident.
     */
    @Test
    void claimAndRunOne_whenTheRunnerIsDisabled_leavesTheQueueUntouched() {
        UUID job = enqueue(JobKind.RESEARCH).id();
        setConfig("ai.job.runner.enabled", "false");

        assertThat(runner.claimAndRunOne()).isFalse();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(attemptOf(job)).isZero();
        assertThat(handler.calls).hasValue(0);
    }

    /** A missing row must not read as "on". Nothing should start spending because of a typo. */
    @Test
    void claimAndRunOne_whenTheEnabledRowIsMissing_failsClosed() {
        enqueue(JobKind.RESEARCH);
        jdbc.update("DELETE FROM app_config WHERE key = 'ai.job.runner.enabled'");
        config.refresh();
        try {
            assertThat(runner.claimAndRunOne()).isFalse();
            assertThat(handler.calls).hasValue(0);
        } finally {
            setConfig("ai.job.runner.enabled", "true");
        }
    }

    // --- fixtures -------------------------------------------------------------

    private GenerationJob enqueue(JobKind kind) {
        return jobs.enqueue(account, null, null, kind, "mimic a product's API");
    }

    private UUID newAccount() {
        return jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by GenerationJobRunnerTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }

    private String statusOf(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private int attemptOf(UUID jobId) {
        return jdbc.queryForObject("SELECT attempt FROM generation_job WHERE id = ?", Integer.class, jobId);
    }

    private String errorCodeOf(UUID jobId) {
        return jdbc.queryForObject("SELECT error_code FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private String errorMessageOf(UUID jobId) {
        return jdbc.queryForObject("SELECT error_message FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private String resultOf(UUID jobId) {
        return jdbc.queryForObject("SELECT result::text FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private long countByStatus(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM generation_job WHERE status = ?", Long.class, status);
    }
}
