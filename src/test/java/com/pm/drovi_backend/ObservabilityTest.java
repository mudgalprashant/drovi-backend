package com.pm.drovi_backend;

import com.pm.drovi_backend.common.Correlation;
import com.pm.drovi_backend.common.CorrelationIdFilter;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.ops.UsageWatch;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5.5 — the part that turns "it broke" into "here is what broke".
 *
 * <p>Two halves. Correlation: every request has carried an id since Phase 1, and background work
 * had none — so a generation's five jobs and dozen model calls could only be reassembled by
 * reading timestamps. And alerting: every control in the system announces itself by <em>refusing
 * somebody</em>, and nothing said a word while a limit was being approached.
 */
@SpringBootTest
class ObservabilityTest extends PostgresTestBase {

    @Autowired
    UsageWatch watch;
    @Autowired
    AppConfigService config;
    @Autowired
    JdbcTemplate jdbc;

    private UUID project;
    private UUID matchedEndpoint;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mock_request_log");
        jdbc.update("DELETE FROM ai_call");
        setConfig("watch.enabled", "true");
        setConfig("watch.spend.percent", "70");
        setConfig("watch.storage.percent", "75");
        setConfig("watch.storage.budget.mb", "400");
        setConfig("watch.unmatched.percent", "25");
        setConfig("watch.unmatched.min.calls", "50");
        setConfig("ai.daily.cost.cap.micros", "1000000");

        UUID account = jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
        project = jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, name, source_product, status, auth_mode)
                VALUES (?, 'Cards', 'Stripe', 'READY', 'NONE') RETURNING id
                """, UUID.class, account);

        // A real endpoint to point matched calls at. endpoint_id IS what "matched" means, so
        // without one every logged call is unmatched and the check can only ever fire.
        UUID collection = jdbc.queryForObject("""
                INSERT INTO sandbox_collection (project_id, code, display_name, key_field)
                VALUES (?, 'cards', 'Cards', 'id') RETURNING id
                """, UUID.class, project);
        UUID group = jdbc.queryForObject(
                "INSERT INTO api_collection (project_id, name) VALUES (?, 'Cards') RETURNING id",
                UUID.class, project);
        matchedEndpoint = jdbc.queryForObject("""
                INSERT INTO api_endpoint (project_id, collection_id, method, path_template, summary,
                                          behavior, data_collection_id)
                VALUES (?, ?, 'GET', '/v1/cards', 'List', 'LIST', ?) RETURNING id
                """, UUID.class, project, group, collection);
    }

    @AfterEach
    void restore() {
        setConfig("ai.daily.cost.cap.micros", "5000000");
        setConfig("watch.storage.budget.mb", "400");
        MDC.clear();
    }

    // --- correlation ----------------------------------------------------------

    /** Background work used to run with an empty MDC — the only lines with nothing to group them. */
    @Test
    void correlation_appliesAnIdToWorkThatDidNotArriveOverHttp() {
        String seen = Correlation.get("job-123", CorrelationIdFilter::current);

        assertThat(seen).isEqualTo("job-123");
    }

    /**
     * Restored, not cleared. Scheduled work runs on pooled and virtual threads that are reused,
     * and clearing unconditionally would strip the id from whatever called us — which on a
     * virtual thread carrier can be an unrelated request.
     */
    @Test
    void correlation_restoresWhateverWasThereBefore() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer");

        Correlation.as("inner", () -> assertThat(CorrelationIdFilter.current()).isEqualTo("inner"));

        assertThat(CorrelationIdFilter.current()).isEqualTo("outer");
    }

    @Test
    void correlation_leavesNothingBehindWhenThereWasNothingBefore() {
        MDC.clear();

        Correlation.as("transient", () -> {
        });

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    /** A throwing unit of work must not leak its id onto the next one. */
    @Test
    void correlation_isRestoredEvenWhenTheWorkThrows() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer");

        try {
            Correlation.as("inner", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the point is what the MDC looks like afterwards
        }

        assertThat(CorrelationIdFilter.current()).isEqualTo("outer");
    }

    // --- spend ----------------------------------------------------------------

    /**
     * The cap stops the spending; it does not tell anyone. A cap reached at 2pm means every
     * generation fails closed until midnight, and the first anyone hears is a user asking why
     * nothing works.
     */
    @Test
    void spend_alertsBeforeTheCapIsReached() {
        spend(800_000);

        assertThat(names(watch.check())).contains("spend");
    }

    @Test
    void spend_saysNothingWhileThereIsPlentyLeft() {
        spend(100_000);

        assertThat(names(watch.check())).doesNotContain("spend");
    }

    /** Every alert names the procedure to follow, or it becomes noise and then gets ignored. */
    @Test
    void everyAlert_namesARunbookAction() {
        spend(900_000);

        assertThat(watch.check()).isNotEmpty().allSatisfy(alert ->
                assertThat(alert.detail()).contains("action=runbook:"));
    }

    /** Yesterday's spend is not today's problem — the same UTC boundary the caps use. */
    @Test
    void spend_countsOnlyToday() {
        spend(900_000);
        jdbc.update("UPDATE ai_call SET created_at = now() - interval '2 days'");

        assertThat(names(watch.check())).doesNotContain("spend");
    }

    // --- storage --------------------------------------------------------------

    @Test
    void storage_alertsWhileThereIsStillRoomToActOnIt() {
        setConfig("watch.storage.budget.mb", "1");
        storedBytes(900_000);

        assertThat(names(watch.check())).contains("storage");
    }

    @Test
    void storage_saysNothingWhenTheDatabaseIsMostlyEmpty() {
        storedBytes(1_000);

        assertThat(names(watch.check())).doesNotContain("storage");
    }

    // --- unmatched routes -----------------------------------------------------

    /**
     * The only check here about the product rather than the platform. A high share of calls
     * matching nothing means generation is producing paths the real product does not have, and
     * users are hitting 404s on their own integration and blaming their code.
     */
    @Test
    void unmatchedRoutes_alertWhenGenerationIsProducingPathsThatDoNotExist() {
        for (int i = 0; i < 60; i++) {
            logCall(i % 2 == 0);
        }

        assertThat(names(watch.check())).contains("unmatchedRoutes");
    }

    @Test
    void unmatchedRoutes_saySilentWhenMostCallsMatch() {
        for (int i = 0; i < 60; i++) {
            logCall(i == 0);
        }

        assertThat(names(watch.check())).doesNotContain("unmatchedRoutes");
    }

    /** Three requests of which one missed is 33% and tells you nothing at all. */
    @Test
    void unmatchedRoutes_needEnoughTrafficToMeanAnything() {
        for (int i = 0; i < 3; i++) {
            logCall(true);
        }

        assertThat(names(watch.check())).doesNotContain("unmatchedRoutes");
    }

    /** An hour's window: a bad afternoon should stop shouting once it is over. */
    @Test
    void unmatchedRoutes_onlyLookAtTheLastHour() {
        for (int i = 0; i < 60; i++) {
            logCall(true);
        }
        jdbc.update("UPDATE mock_request_log SET created_at = now() - interval '3 hours'");

        assertThat(names(watch.check())).doesNotContain("unmatchedRoutes");
    }

    // --- the switch -----------------------------------------------------------

    @Test
    void whenDisabled_nothingIsEvaluated() {
        spend(900_000);
        setConfig("watch.enabled", "false");

        assertThat(watch.check()).isEmpty();
    }

    /** A missing row must not turn alerting on quietly, nor off quietly — code default is off. */
    @Test
    void whenTheEnabledRowIsMissing_nothingIsEvaluated() {
        spend(900_000);
        jdbc.update("DELETE FROM app_config WHERE key = 'watch.enabled'");
        config.refresh();
        try {
            assertThat(watch.check()).isEmpty();
        } finally {
            setConfig("watch.enabled", "true");
        }
    }

    // --- fixtures -------------------------------------------------------------

    private static List<String> names(List<UsageWatch.Alert> alerts) {
        return alerts.stream().map(UsageWatch.Alert::name).toList();
    }

    private void spend(long micros) {
        UUID account = jdbc.queryForObject("SELECT account_id FROM sandbox_project WHERE id = ?",
                UUID.class, project);
        jdbc.update("""
                INSERT INTO ai_call (account_id, provider_code, model, purpose, cost_micros, status)
                VALUES (?, 'GEMINI', 'gemini-3.7-flash', 'SEED', ?, 'OK')
                """, account, micros);
    }

    /** Written straight to the counter: the trigger owns it, and this stands in for one. */
    private void storedBytes(long bytes) {
        jdbc.update("UPDATE sandbox_collection SET stored_bytes = ? WHERE project_id = ?", bytes, project);
    }

    /** A null {@code endpoint_id} is exactly what "nothing matched" means in this table. */
    private void logCall(boolean unmatched) {
        jdbc.update("""
                INSERT INTO mock_request_log
                    (project_id, endpoint_id, method, path, status_code, latency_ms)
                VALUES (?, ?, 'GET', '/v1/cards', ?, 3)
                """, project, unmatched ? null : matchedEndpoint, unmatched ? 404 : 200);
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by ObservabilityTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
