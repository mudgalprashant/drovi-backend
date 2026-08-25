package com.pm.drovi_backend;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5.3 — abuse control on {@code /s/**}, the one surface anyone on the internet can reach.
 *
 * <p>A sandbox base URL is unauthenticated by design when {@code auth_mode = NONE}, and every
 * call writes a {@code mock_request_log} row. An unbounded caller therefore costs storage as well
 * as CPU, on a 500 MB database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxRateLimitTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppConfigService config;
    @Autowired
    JdbcTemplate jdbc;

    private UUID project;

    @BeforeEach
    void setUp() {
        // A fresh project each time: the limiter's windows are keyed by project id, so a new id
        // is a clean bucket without reaching into the limiter's internals.
        UUID account = jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
        project = jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, name, source_product, status, auth_mode)
                VALUES (?, 'Cards', 'Stripe', 'READY', 'NONE') RETURNING id
                """, UUID.class, account);

        setConfig("runtime.rate.limit.enabled", "true");
        setConfig("runtime.rate.limit.per.minute", "600");
        setConfig("runtime.rate.limit.per.ip.per.minute", "120");
    }

    @AfterEach
    void restore() {
        setConfig("runtime.rate.limit.enabled", "true");
        setConfig("runtime.rate.limit.per.minute", "600");
        setConfig("runtime.rate.limit.per.ip.per.minute", "120");
    }

    // --- the limit ------------------------------------------------------------

    @Test
    void withinTheLimit_callsAreServedNormally() throws Exception {
        setConfig("runtime.rate.limit.per.minute", "5");

        for (int i = 0; i < 5; i++) {
            mvc.perform(get(url())).andExpect(status().isNotFound());
        }
    }

    /** 429 with Retry-After, because a caller that is told to back off should be told for how long. */
    @Test
    void pastTheLimit_theSandboxAnswers429WithRetryAfter() throws Exception {
        setConfig("runtime.rate.limit.per.minute", "2");
        mvc.perform(get(url()));
        mvc.perform(get(url()));

        mvc.perform(get(url()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    /**
     * The refusal wears the sandbox's platform shape, not the console's. A caller's own error
     * handling is pointed at the imitated product, and a Drovi-shaped body is one it never sees.
     */
    @Test
    void aRefusal_isShapedLikeEveryOtherSandboxFailure() throws Exception {
        setConfig("runtime.rate.limit.per.minute", "1");
        mvc.perform(get(url()));

        mvc.perform(get(url()))
                .andExpect(jsonPath("$.error.code").exists())
                .andExpect(jsonPath("$.error.message").exists())
                // The console's envelope carries a correlation id; the sandbox's must not.
                .andExpect(jsonPath("$.error.correlationId").doesNotExist());
    }

    /** One sandbox's traffic must not exhaust another's budget. */
    @Test
    void oneProjectHittingItsLimit_doesNotAffectAnother() throws Exception {
        setConfig("runtime.rate.limit.per.minute", "1");
        mvc.perform(get(url()));
        mvc.perform(get(url())).andExpect(status().isTooManyRequests());

        UUID other = newProject();
        mvc.perform(get("/s/" + other + "/v1/cards")).andExpect(status().isNotFound());
    }

    // --- cost -----------------------------------------------------------------

    /**
     * The point of checking before the runtime: a guard that first reads a row and writes another
     * costs more under abuse than it saves.
     */
    @Test
    void aRefusedCall_isNotWrittenToTheRequestLog() throws Exception {
        setConfig("runtime.rate.limit.per.minute", "1");
        mvc.perform(get(url()));
        long afterFirst = logCount();

        mvc.perform(get(url())).andExpect(status().isTooManyRequests());

        assertThat(logCount()).as("a refused call must not cost a log row").isEqualTo(afterFirst);
    }

    // --- configuration --------------------------------------------------------

    @Test
    void whenDisabled_nothingIsLimited() throws Exception {
        setConfig("runtime.rate.limit.enabled", "false");
        setConfig("runtime.rate.limit.per.minute", "1");

        for (int i = 0; i < 5; i++) {
            mvc.perform(get(url())).andExpect(status().isNotFound());
        }
    }

    /** A missing row disables limiting rather than enabling it — the seeded row is the switch. */
    @Test
    void whenTheEnabledRowIsMissing_nothingIsLimited() throws Exception {
        jdbc.update("DELETE FROM app_config WHERE key = 'runtime.rate.limit.enabled'");
        config.refresh();
        try {
            setConfig("runtime.rate.limit.per.minute", "1");
            mvc.perform(get(url())).andExpect(status().isNotFound());
            mvc.perform(get(url())).andExpect(status().isNotFound());
        } finally {
            setConfig("runtime.rate.limit.enabled", "true");
        }
    }

    /**
     * A fat-fingered zero should stop a sandbox loudly rather than quietly remove its only guard.
     * "No traffic" is a strange setting; "unlimited" is a dangerous one.
     */
    @Test
    void aZeroLimit_meansNoTrafficRatherThanUnlimited() throws Exception {
        setConfig("runtime.rate.limit.per.minute", "0");

        mvc.perform(get(url())).andExpect(status().isTooManyRequests());
    }

    // --- fixtures -------------------------------------------------------------

    private String url() {
        return "/s/" + project + "/v1/cards";
    }

    private UUID newProject() {
        UUID account = jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, name, source_product, status, auth_mode)
                VALUES (?, 'Other', 'Stripe', 'READY', 'NONE') RETURNING id
                """, UUID.class, account);
    }

    private long logCount() {
        return jdbc.queryForObject("SELECT count(*) FROM mock_request_log WHERE project_id = ?",
                Long.class, project);
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by SandboxRateLimitTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
