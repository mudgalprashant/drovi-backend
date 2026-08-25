package com.pm.drovi_backend;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5.4 and thread N — a replica whose errors look like the product's.
 *
 * <p>The happy path has been faithful since Phase 0; the error path never was. Ask a mock of
 * Stripe for a card that does not exist and it answered <em>Drovi's</em> shape, so the caller's
 * error-handling branch — the one they most want to exercise against a mock — received a payload
 * the real product never sends.
 *
 * <p>The other half matters just as much: <strong>Drovi's own failures must stay recognisable.</strong>
 * A user staring at a 429 needs to know whether the mock was playing a part or the platform was
 * refusing them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorEnvelopeTest extends PostgresTestBase {

    /** A Stripe-ish shape: nested, with a type the product's own clients switch on. */
    private static final String STRIPE_SHAPE = """
            {"error":{"type":"invalid_request_error","code":"{{code}}","message":"{{message}}"}}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    AppConfigService config;
    @Autowired
    JdbcTemplate jdbc;

    private UUID project;
    private UUID collection;

    @BeforeEach
    void setUp() {
        setConfig("runtime.rate.limit.enabled", "false");

        UUID account = jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
        project = jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, name, source_product, status, auth_mode)
                VALUES (?, 'Cards', 'Stripe', 'READY', 'NONE') RETURNING id
                """, UUID.class, account);
        collection = jdbc.queryForObject("""
                INSERT INTO sandbox_collection (project_id, code, display_name, key_field)
                VALUES (?, 'cards', 'Cards', 'id') RETURNING id
                """, UUID.class, project);
        UUID group = jdbc.queryForObject(
                "INSERT INTO api_collection (project_id, name) VALUES (?, 'Cards') RETURNING id",
                UUID.class, project);
        jdbc.update("""
                INSERT INTO api_endpoint (project_id, collection_id, method, path_template, summary,
                                          behavior, data_collection_id, key_param)
                VALUES (?, ?, 'GET', '/v1/cards/{cardId}', 'Get', 'GET', ?, 'cardId')
                """, project, group, collection);
    }

    // --- in character ---------------------------------------------------------

    /** The case thread N was opened for. */
    @Test
    void aMissingRecord_answersInTheProductsShape() throws Exception {
        useEnvelope(STRIPE_SHAPE);

        mvc.perform(get("/s/" + project + "/v1/cards/card_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("card_missing")));
    }

    /** A route the product does not have is also something the product answers for itself. */
    @Test
    void anUnmatchedRoute_answersInTheProductsShape() throws Exception {
        useEnvelope(STRIPE_SHAPE);

        mvc.perform(get("/s/" + project + "/v1/nothing/here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    /** A rejected key is one of the branches a caller most wants to exercise. */
    @Test
    void aRejectedKey_answersInTheProductsShape() throws Exception {
        useEnvelope(STRIPE_SHAPE);
        jdbc.update("UPDATE sandbox_project SET auth_mode = 'BEARER' WHERE id = ?", project);

        mvc.perform(get("/s/" + project + "/v1/cards/card_1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    /** Not every product nests its errors. The template is the product's, not a Stripe copy. */
    @Test
    void aFlatErrorShape_worksJustAsWell() throws Exception {
        useEnvelope("{\"detail\":\"{{message}}\",\"status\":\"{{status}}\"}");

        mvc.perform(get("/s/" + project + "/v1/cards/card_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    /** The status code is the product's own, and the envelope only changes the body. */
    @Test
    void theEnvelope_doesNotChangeTheStatusCode() throws Exception {
        useEnvelope(STRIPE_SHAPE);

        mvc.perform(get("/s/" + project + "/v1/cards/card_missing"))
                .andExpect(status().isNotFound());
    }

    // --- out of character -----------------------------------------------------

    /**
     * The other half of the rule. Dressing a platform failure as the product would tell a user
     * their integration is broken when in fact we are.
     */
    @Test
    void ourOwnFailures_keepOurOwnShape() throws Exception {
        useEnvelope(STRIPE_SHAPE);
        setConfig("runtime.rate.limit.enabled", "true");
        setConfig("runtime.rate.limit.per.minute", "1");
        try {
            mvc.perform(get("/s/" + project + "/v1/cards/card_1"));

            mvc.perform(get("/s/" + project + "/v1/cards/card_1"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.error.type").doesNotExist());
        } finally {
            setConfig("runtime.rate.limit.enabled", "false");
        }
    }

    /** A sandbox that does not exist is not a product error — there is no product yet. */
    @Test
    void anUnknownSandbox_keepsOurOwnShape() throws Exception {
        mvc.perform(get("/s/" + UUID.randomUUID() + "/v1/cards/card_1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SANDBOX_NOT_FOUND"));
    }

    // --- projects without one -------------------------------------------------

    /** Every project that predates this keeps working, unchanged. */
    @Test
    void withNoEnvelope_theSandboxAnswersExactlyAsItAlwaysDid() throws Exception {
        mvc.perform(get("/s/" + project + "/v1/cards/card_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").exists());
    }

    /** A success is a success; the envelope has nothing to do with it. */
    @Test
    void theEnvelope_neverTouchesASuccessfulResponse() throws Exception {
        useEnvelope(STRIPE_SHAPE);
        jdbc.update("""
                INSERT INTO sandbox_record (project_id, collection_id, record_key, data)
                VALUES (?, ?, 'card_1', '{"id":"card_1","status":"ACTIVE"}'::jsonb)
                """, project, collection);

        mvc.perform(get("/s/" + project + "/v1/cards/card_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // --- fixtures -------------------------------------------------------------

    private void useEnvelope(String json) {
        jdbc.update("UPDATE sandbox_project SET error_envelope = ?::jsonb WHERE id = ?", json, project);
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by ErrorEnvelopeTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
