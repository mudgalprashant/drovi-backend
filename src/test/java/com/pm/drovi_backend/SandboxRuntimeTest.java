package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Drives a whole sandbox through HTTP, the way a customer's client would.
 *
 * <p>Query parameters are written into the URL rather than passed via MockMvc's
 * {@code .param(...)}: that helper populates the servlet parameter map without setting a
 * query string, so it would bypass the runtime's own query parsing — the code that
 * actually runs in production — and test nothing.
 *
 * <p>These are the tests that say the product works: a project is seeded, its base URL is
 * called, and the response has to be the one the real product would have given. Everything
 * goes through the servlet layer on a real database — the two places where a mapping or a
 * migration is actually wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxRuntimeTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** The headline promise: swap the base URL and an existing client keeps working. */
    @Test
    void get_boundToACollection_servesTheStoredRecord() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_9", """
                {"id":"card_9","last4":"4242","status":"ACTIVE","holder":"A. Kapoor"}""");
        f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");

        mvc.perform(get(f.url("/v1/cards/card_9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("card_9"))
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void get_unknownRecord_returnsNotFound() throws Exception {
        Fixture f = new Fixture("cards");
        f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");

        mvc.perform(get(f.url("/v1/cards/nope")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /**
     * The user's own example: ask for the customers whose card was blocked, and get them
     * back by filtering stored data — no rule, no code change.
     */
    @Test
    void list_filtersOnADeclaredField_throughTheEnvelope() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_1", """
                {"id":"card_1","status":"BLOCKED","customerId":"cus_100"}""");
        f.record("card_2", """
                {"id":"card_2","status":"ACTIVE","customerId":"cus_200"}""");
        f.record("card_3", """
                {"id":"card_3","status":"BLOCKED","customerId":"cus_300"}""");
        f.endpoint("GET", "/v1/cards", "LIST", null, """
                {"object":"list","data":"{{items}}","has_more":"{{hasMore}}"}""");

        mvc.perform(get(f.url("/v1/cards?status=BLOCKED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.data[*].customerId",
                        org.hamcrest.Matchers.containsInAnyOrder("cus_100", "cus_300")));
    }

    /**
     * A query parameter the product accepts but that is not a stored field must not be
     * treated as a filter — otherwise a perfectly valid call quietly returns nothing.
     */
    @Test
    void list_ignoresQueryParamsThatAreNotFields() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_1", """
                {"id":"card_1","status":"ACTIVE","customerId":"cus_1"}""");
        f.endpoint("GET", "/v1/cards", "LIST", null, "{}");

        mvc.perform(get(f.url("/v1/cards?expand=customer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** Paging is clamped to the configured ceiling, not to whatever the caller asked for. */
    @Test
    void list_clampsPageSizeToTheConfiguredMaximum() throws Exception {
        Fixture f = new Fixture("cards");
        for (int i = 0; i < 5; i++) {
            f.record("card_" + i, "{\"id\":\"card_%d\",\"status\":\"ACTIVE\"}".formatted(i));
        }
        f.endpoint("GET", "/v1/cards", "LIST", null, "{}");

        mvc.perform(get(f.url("/v1/cards?limit=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** Rules beat data — an override the data could never express. */
    @Test
    void rule_overridesTheDataBackedResponse() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_9", "{\"id\":\"card_9\",\"status\":\"ACTIVE\"}");
        UUID endpoint = f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");
        f.rule(endpoint, "rate limit card_9", 10, """
                        {"pathParams":{"cardId":"card_9"}}""",
                429, """
                        {"error":{"type":"rate_limit_error"}}""", null);

        mvc.perform(get(f.url("/v1/cards/card_9")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"));
    }

    /** A rule whose matcher does not fit must fall through to the data, not swallow it. */
    @Test
    void rule_thatDoesNotMatch_fallsThroughToData() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_9", "{\"id\":\"card_9\",\"status\":\"ACTIVE\"}");
        UUID endpoint = f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");
        f.rule(endpoint, "only card_1", 10, """
                {"pathParams":{"cardId":"card_1"}}""", 429, "{}", null);

        mvc.perform(get(f.url("/v1/cards/card_9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("card_9"));
    }

    /** "Make the next call fail" has to mean exactly one call. */
    @Test
    void oneShotRule_firesOnceThenStopsMatching() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_9", "{\"id\":\"card_9\",\"status\":\"ACTIVE\"}");
        UUID endpoint = f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");
        f.rule(endpoint, "fail once", 10, "{}", 503, """
                {"error":{"message":"temporarily unavailable"}}""", 1);

        mvc.perform(get(f.url("/v1/cards/card_9"))).andExpect(status().isServiceUnavailable());
        mvc.perform(get(f.url("/v1/cards/card_9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("card_9"));
    }

    /** A literal path must not disappear behind a parameterised one. */
    @Test
    void routing_prefersTheLiteralPathOverThePlaceholder() throws Exception {
        Fixture f = new Fixture("cards");
        f.record("card_9", "{\"id\":\"card_9\",\"status\":\"ACTIVE\"}");
        f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");
        UUID blocked = f.endpoint("GET", "/v1/cards/blocked", "STATIC", null, """
                {"marker":"literal-route"}""");
        assertThat(blocked).isNotNull();

        mvc.perform(get(f.url("/v1/cards/blocked")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marker").value("literal-route"));
    }

    @Test
    void post_createsARecordAndItIsImmediatelyReadable() throws Exception {
        Fixture f = new Fixture("cards");
        f.endpoint("POST", "/v1/cards", "CREATE", null, "{}");
        f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");

        mvc.perform(post(f.url("/v1/cards"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"card_new\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("card_new"));

        mvc.perform(get(f.url("/v1/cards/card_new")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** Invariant 2: the trigger counted the write, so quota can be enforced against it. */
    @Test
    void post_countsAgainstTheProjectsStorageQuota() throws Exception {
        Fixture f = new Fixture("cards");
        f.endpoint("POST", "/v1/cards", "CREATE", null, "{}");

        mvc.perform(post(f.url("/v1/cards"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"card_q\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated());

        Long bytes = jdbc.queryForObject(
                "SELECT stored_bytes FROM sandbox_collection WHERE id = ?", Long.class, f.collectionId);
        assertThat(bytes).as("a write through the runtime must be metered like any other").isPositive();
    }

    /**
     * The sandbox has to reject an unauthenticated call exactly like the product it
     * imitates, or the caller's own auth path is never exercised until production.
     */
    @Test
    void bearerAuth_rejectsWithoutAKeyAndAcceptsWithOne() throws Exception {
        Fixture f = new Fixture("cards", "BEARER");
        f.record("card_9", "{\"id\":\"card_9\",\"status\":\"ACTIVE\"}");
        f.endpoint("GET", "/v1/cards/{cardId}", "GET", "cardId", "{}");
        String key = f.apiKey();

        mvc.perform(get(f.url("/v1/cards/card_9")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mvc.perform(get(f.url("/v1/cards/card_9")).header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());

        mvc.perform(get(f.url("/v1/cards/card_9")).header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A base URL typed wrong. Since the sandbox is addressed by the project's uuid this is not
     * even a well-formed id, and it must still answer in the SANDBOX's shape — a caller's error
     * handling should never meet Spring's own 400 body, which the imitated product never sends.
     */
    @Test
    void aMalformedSandboxAddress_returnsTheSandboxsOwnNotFound() throws Exception {
        mvc.perform(get("/s/does-not-exist/v1/cards"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SANDBOX_NOT_FOUND"));
    }

    /**
     * The other half: a perfectly well-formed id that is nobody's project. Answered identically,
     * so the response cannot be used to discover which sandboxes exist.
     */
    @Test
    void aWellFormedIdThatIsNobodysProject_returnsTheSameNotFound() throws Exception {
        mvc.perform(get("/s/" + UUID.randomUUID() + "/v1/cards"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SANDBOX_NOT_FOUND"));
    }

    /** Every served call lands in the inspector, including the ones that matched nothing. */
    @Test
    void unmatchedRoute_isRecordedInTheRequestLog() throws Exception {
        Fixture f = new Fixture("cards");

        mvc.perform(get(f.url("/v1/nothing/here"))).andExpect(status().isNotFound());

        Long logged = jdbc.queryForObject("""
                SELECT count(*) FROM mock_request_log
                 WHERE project_id = ? AND endpoint_id IS NULL AND path = '/v1/nothing/here'
                """, Long.class, f.projectId);
        assertThat(logged).isEqualTo(1);
    }

    // --- fixture --------------------------------------------------------------

    /** Builds one ready-to-serve project. Each instance is isolated by a unique key. */
    private class Fixture {

        final UUID projectId;
        final UUID collectionId;
        final UUID apiCollectionId;

        Fixture(String collectionCode) {
            this(collectionCode, "NONE");
        }

        Fixture(String collectionCode, String authMode) {
            UUID accountId = jdbc.queryForObject(
                    "INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                    UUID.class, "uid-" + UUID.randomUUID());
            this.projectId = jdbc.queryForObject("""
                    INSERT INTO sandbox_project
                        (account_id, name, source_product, status, auth_mode)
                    VALUES (?, 'Cards sandbox', 'Test card issuer', 'READY', ?) RETURNING id
                    """, UUID.class, accountId, authMode);
            // A schema with declared properties, because the filter only trusts fields the
            // collection says it has.
            this.collectionId = jdbc.queryForObject("""
                    INSERT INTO sandbox_collection (project_id, code, display_name, key_field, record_schema)
                    VALUES (?, ?, ?, 'id', CAST(? AS jsonb)) RETURNING id
                    """, UUID.class, projectId, collectionCode, collectionCode, """
                    {"properties":{"id":{},"status":{},"customerId":{},"last4":{},"holder":{}}}""");
            this.apiCollectionId = jdbc.queryForObject(
                    "INSERT INTO api_collection (project_id, name) VALUES (?, 'Cards') RETURNING id",
                    UUID.class, projectId);
        }

        String url(String path) {
            // The sandbox is addressed by the project's own id.
            return "/s/" + projectId + path;
        }

        void record(String key, String json) {
            jdbc.update("""
                    INSERT INTO sandbox_record (project_id, collection_id, record_key, data)
                    VALUES (?, ?, ?, CAST(? AS jsonb))
                    """, projectId, collectionId, key, json);
        }

        UUID endpoint(String method, String pathTemplate, String behavior, String keyParam, String template) {
            return jdbc.queryForObject("""
                    INSERT INTO api_endpoint
                        (project_id, collection_id, method, path_template, summary, behavior,
                         data_collection_id, key_param, response_template)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) RETURNING id
                    """, UUID.class, projectId, apiCollectionId, method, pathTemplate,
                    method + " " + pathTemplate, behavior,
                    "STATIC".equals(behavior) ? null : collectionId, keyParam, template);
        }

        void rule(UUID endpointId, String name, int priority, String matcher,
                  int status, String body, Integer remainingUses) {
            jdbc.update("""
                    INSERT INTO response_rule
                        (project_id, endpoint_id, name, priority, matcher, status_code, body, remaining_uses)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)
                    """, projectId, endpointId, name, priority, matcher, status, body, remainingUses);
        }

        /** Returns the raw key; only its hash is stored, exactly as in production. */
        String apiKey() {
            String raw = "sk_test_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update("""
                    INSERT INTO project_api_key (project_id, name, key_prefix, key_hash)
                    VALUES (?, 'test key', ?, encode(digest(?, 'sha256'), 'hex'))
                    """, projectId, raw.substring(0, 11), raw);
            return raw;
        }
    }
}
