package com.pm.drovi_backend;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.JobHandler;
import com.pm.drovi_backend.generation.JobKind;
import com.pm.drovi_backend.generation.JobStore;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does {@code @Scheduled} actually fire?
 *
 * <p>An unreasonable-looking question with a specific reason behind it. Spring Boot does not
 * enable scheduling on its own: without {@code @EnableScheduling} no post-processor is
 * registered, every {@code @Scheduled} method is inert, and <em>nothing reports it</em>. This
 * codebase already contains one live instance of exactly that mistake — four
 * {@code @Cacheable} annotations that do nothing, because no {@code @EnableCaching} exists
 * (backend context, open thread Q).
 *
 * <p>The failure mode here would be jobs sitting QUEUED forever with no error to explain it,
 * which is a bad afternoon to have during a demo. So this test asserts the wiring rather than
 * trusting the annotation to mean what it says. It is the only test that waits on the clock,
 * and it waits on a latch rather than a sleep.
 */
@SpringBootTest(properties = {
        // The scheduler is off for the rest of the suite; this test is why it can be trusted
        // when it is on. Tight cadence so the test is quick rather than merely eventual.
        "drovi.jobs.scheduler-enabled=true",
        "drovi.jobs.poll-interval-ms=200",
        "drovi.jobs.initial-delay-ms=0"
})
@Import(JobSchedulingTest.TrivialHandlerConfig.class)
class JobSchedulingTest extends PostgresTestBase {

    /** Succeeds whatever it is given. What is under test is the schedule, not the handler. */
    static class TrivialHandler implements JobHandler {

        @Override
        public JobKind kind() {
            return JobKind.RESEARCH;
        }

        @Override
        public Map<String, Object> handle(GenerationJob job) {
            return Map.of("scheduled", true);
        }
    }

    @TestConfiguration
    static class TrivialHandlerConfig {
        @Bean
        TrivialHandler trivialHandler() {
            return new TrivialHandler();
        }
    }

    @Autowired
    JobStore jobs;
    @Autowired
    AppConfigService config;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aQueuedJob_isPickedUpWithNobodyCallingTheRunner() throws InterruptedException {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES ('ai.job.runner.enabled', 'true', 'BOOLEAN', 'set by JobSchedulingTest')
                ON CONFLICT (key) DO UPDATE SET value = 'true'
                """);
        config.refresh();

        // The scheduler is already ticking by the time this method runs, and other test
        // classes share this database. Start from an empty queue so the latch can only be
        // reached by the job enqueued below.
        jdbc.update("DELETE FROM generation_job");

        UUID account = jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
        GenerationJob job = jobs.enqueue(account, null, null, JobKind.RESEARCH, "mimic a product's API");

        // Waits for THIS job rather than for "a handler ran". The first version of this test
        // used a latch the handler counted down, and it passed against a leftover job from
        // another test class while its own job sat untouched — a green test proving nothing.
        assertThat(awaitStatus(job.id(), "SUCCEEDED", Duration.ofSeconds(20)))
                .as("no @EnableScheduling means every @Scheduled method is inert and silent")
                .isTrue();
    }

    /**
     * Polls rather than sleeps a fixed span: it returns as soon as the state arrives, so the
     * timeout can be generous without making the suite slow. The assertion is "the scheduler
     * runs at all", not "it runs within 200 ms".
     */
    private boolean awaitStatus(UUID jobId, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM generation_job WHERE id = ?", String.class, jobId);
            if (expected.equals(status)) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return false;
    }
}
