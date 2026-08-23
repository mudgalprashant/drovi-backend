package com.pm.drovi_backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns {@code @Scheduled} on.
 *
 * <p>This class exists because Spring Boot does <em>not</em> enable scheduling for you.
 * Without {@code @EnableScheduling} no post-processor is registered, every {@code @Scheduled}
 * method is inert, and nothing anywhere reports it — the job runner would simply never claim
 * anything, and the first symptom would be jobs sitting QUEUED with no error to explain it.
 *
 * <p>That is not a hypothetical. The same shape of mistake is already live in this codebase
 * with {@code @EnableCaching} (see the backend context's open thread Q), where four
 * {@code @Cacheable} annotations do nothing. Hence
 * {@code JobSchedulingTest.aQueuedJob_isPickedUpWithNobodyCallingTheRunner}, which proves the
 * scheduler actually fires rather than trusting the annotation to mean what it says.
 *
 * <p>Off in tests, where a background thread claiming rows makes every other test's fixtures
 * a race. Tests that need the state machine call the runner directly; the one test that needs
 * the schedule turns it back on for itself.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "drovi.jobs.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
