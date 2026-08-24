package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Endpoints and rules through the console — the piece that completes the loop.
 *
 * <p>The first test is the one that matters: a whole working sandbox built with **no SQL at
 * all**. Until this phase, a console-created project had no routes and answered 404 to
 * everything, and every earlier test had to insert one by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EndpointsAndRulesTest.StubFirebase.class)
class EndpointsAndRulesTest extends PostgresTestBase {

    @TestConfiguration
    static class StubFirebase {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").subject(token)
                    .claim("email", token + "@example.test")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    private static String user() {
        return "uid-" + UUID.randomUUID();
    }

    /**
     * Describe a product, seed its data, declare a route, call the base URL — all over
     * HTTP. This is what "usable without SQL" means, and it is the exit criterion for the
     * console half of Phase 2.
     */
    @Test
    void aWholeSandbox_canBeBuiltAndServed_withNoSqlAtAll() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        String collection = createCollection(owner, project, "cards");

        seed(owner, project, collection, """
                {"records":[
                  {"id":"card_1","status":"BLOCKED","customerId":"cus_100"},
                  {"id":"card_2","status":"ACTIVE","customerId":"cus_200"},
                  {"id":"card_3","status":"BLOCKED","customerId":"cus_300"}]}""");

        createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards/{cardId}","apiGroup":"Cards",
                 "behavior":"GET","dataCollectionId":"%s","keyParam":"cardId"}""".formatted(collection));
        createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards","apiGroup":"Cards",
                 "behavior":"LIST","dataCollectionId":"%s",
                 "responseTemplate":{"object":"list","data":"{{items}}","has_more":"{{hasMore}}"}}"""
                .formatted(collection));

        // The sandbox now serves the generated shape.
        mvc.perform(get("/s/" + key + "/v1/cards/card_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.customerId").value("cus_100"));

        // And the user's own example: the blocked ones, by filtering stored data.
        mvc.perform(get("/s/" + key + "/v1/cards?status=BLOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    /** Rules beat data — the override layer, created through the console. */
    @Test
    void aRuleCreatedInTheConsole_overridesTheData() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        String collection = createCollection(owner, project, "cards");
        seed(owner, project, collection, "{\"record\":{\"id\":\"card_9\",\"status\":\"ACTIVE\"}}");
        String endpoint = createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards/{cardId}","behavior":"GET",
                 "dataCollectionId":"%s","keyParam":"cardId"}""".formatted(collection));

        mvc.perform(post("/api/v1/projects/" + project + "/endpoints/" + endpoint + "/rules")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"rate limit card_9","priority":10,
                                 "matcher":{"pathParams":{"cardId":"card_9"}},
                                 "statusCode":429,"body":{"error":{"type":"rate_limit_error"}}}"""))
                .andExpect(status().isCreated());

        mvc.perform(get("/s/" + key + "/v1/cards/card_9"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"));
    }

    /** "Make the next call fail" must mean exactly one call. */
    @Test
    void aOneShotRule_firesOnceThenFallsBackToData() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        String collection = createCollection(owner, project, "cards");
        seed(owner, project, collection, "{\"record\":{\"id\":\"card_9\",\"status\":\"ACTIVE\"}}");
        String endpoint = createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards/{cardId}","behavior":"GET",
                 "dataCollectionId":"%s","keyParam":"cardId"}""".formatted(collection));

        mvc.perform(post("/api/v1/projects/" + project + "/endpoints/" + endpoint + "/rules")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fail once\",\"statusCode\":503,\"remainingUses\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/s/" + key + "/v1/cards/card_9")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/s/" + key + "/v1/cards/card_9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("card_9"));
    }

    /** Disabling a rule must take effect immediately — the route table is not cached. */
    @Test
    void disablingARule_takesEffectOnTheNextCall() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        String collection = createCollection(owner, project, "cards");
        seed(owner, project, collection, "{\"record\":{\"id\":\"c1\",\"status\":\"ACTIVE\"}}");
        String endpoint = createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards/{cardId}","behavior":"GET",
                 "dataCollectionId":"%s","keyParam":"cardId"}""".formatted(collection));

        String rule = mapper.readTree(mvc.perform(
                        post("/api/v1/projects/" + project + "/endpoints/" + endpoint + "/rules")
                                .header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"statusCode\":503}"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get("/s/" + key + "/v1/cards/c1")).andExpect(status().isServiceUnavailable());

        mvc.perform(patch("/api/v1/projects/" + project + "/rules/" + rule)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/s/" + key + "/v1/cards/c1")).andExpect(status().isOk());
    }

    /** Editing a path must move the live route, not orphan it. */
    @Test
    void editingAnEndpointsPath_movesTheLiveRoute() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        String collection = createCollection(owner, project, "cards");
        seed(owner, project, collection, "{\"record\":{\"id\":\"c1\"}}");
        String endpoint = createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/card/{cardId}","behavior":"GET",
                 "dataCollectionId":"%s","keyParam":"cardId"}""".formatted(collection));

        mvc.perform(get("/s/" + key + "/v1/card/c1")).andExpect(status().isOk());

        mvc.perform(patch("/api/v1/projects/" + project + "/endpoints/" + endpoint)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pathTemplate\":\"/v1/cards/{cardId}\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/s/" + key + "/v1/card/c1")).andExpect(status().isNotFound());
        mvc.perform(get("/s/" + key + "/v1/cards/c1")).andExpect(status().isOk());
    }

    /** A literal segment must outrank a placeholder, or a real route becomes unreachable. */
    @Test
    void aLiteralPath_outranksAParameterisedOne() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        String collection = createCollection(owner, project, "cards");
        seed(owner, project, collection, "{\"record\":{\"id\":\"blocked\"}}");

        createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards/{cardId}","behavior":"GET",
                 "dataCollectionId":"%s","keyParam":"cardId"}""".formatted(collection));
        createEndpoint(owner, project, """
                {"method":"GET","pathTemplate":"/v1/cards/blocked","behavior":"STATIC",
                 "responseTemplate":{"marker":"literal-route"}}""");

        mvc.perform(get("/s/" + key + "/v1/cards/blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marker").value("literal-route"));
    }

    // --- validation ----------------------------------------------------------

    @Test
    void aDataBackedEndpointWithoutACollection_isRejectedWithAClearMessage() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");

        mvc.perform(post("/api/v1/projects/" + project + "/endpoints")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"GET\",\"pathTemplate\":\"/v1/cards\",\"behavior\":\"LIST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** Tenant isolation, at the point where it is easiest to get wrong. */
    @Test
    void anEndpointCannotBindToAnotherProjectsDataCollection() throws Exception {
        String owner = user();
        String mine = createProject(owner, "Mine");
        String theirs = createProject(user(), "Theirs");
        String theirCollection = createCollection(userOf(theirs), theirs, "cards");

        mvc.perform(post("/api/v1/projects/" + mine + "/endpoints")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"GET","pathTemplate":"/v1/x","behavior":"LIST",
                                 "dataCollectionId":"%s"}""".formatted(theirCollection)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicateRoute_isAConflict() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        String body = "{\"method\":\"GET\",\"pathTemplate\":\"/v1/ping\",\"behavior\":\"STATIC\"}";

        mvc.perform(post("/api/v1/projects/" + project + "/endpoints")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/projects/" + project + "/endpoints")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void aPathNotStartingWithSlash_isRejected() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");

        mvc.perform(post("/api/v1/projects/" + project + "/endpoints")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"GET\",\"pathTemplate\":\"v1/ping\",\"behavior\":\"STATIC\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Deleting a group that still holds routes would silently break them. */
    @Test
    void deletingAnApiGroupThatStillHoldsEndpoints_isRefused() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        String endpoint = createEndpoint(owner, project,
                "{\"method\":\"GET\",\"pathTemplate\":\"/v1/ping\",\"apiGroup\":\"Cards\",\"behavior\":\"STATIC\"}");

        String groupId = mapper.readTree(mvc.perform(get("/api/v1/projects/" + project + "/endpoints/" + endpoint)
                        .header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsString()).get("apiGroupId").asString();

        mvc.perform(delete("/api/v1/projects/" + project + "/api-groups/" + groupId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict());
    }

    @Test
    void aStrangerCannotListOrCreateEndpoints() throws Exception {
        String project = createProject(user(), "Private");
        String stranger = user();

        mvc.perform(get("/api/v1/projects/" + project + "/endpoints")
                .header("Authorization", "Bearer " + stranger)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/projects/" + project + "/endpoints")
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"GET\",\"pathTemplate\":\"/x\",\"behavior\":\"STATIC\"}"))
                .andExpect(status().isNotFound());
    }

    // --- inspector -----------------------------------------------------------

    /** Every served call shows up, and an unmatched one is findable on its own. */
    @Test
    void theInspector_showsServedCalls_andIsolatesTheUnmatchedOnes() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        createEndpoint(owner, project,
                "{\"method\":\"GET\",\"pathTemplate\":\"/v1/ping\",\"behavior\":\"STATIC\"," +
                        "\"responseTemplate\":{\"ok\":true}}");

        mvc.perform(get("/s/" + key + "/v1/ping")).andExpect(status().isOk());
        mvc.perform(get("/s/" + key + "/v1/nope")).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/projects/" + project + "/requests")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        // The debugging view: calls nothing served, which usually means a wrong path.
        mvc.perform(get("/api/v1/projects/" + project + "/requests?unmatchedOnly=true")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].path").value("/v1/nope"))
                .andExpect(jsonPath("$.items[0].matched").value(false));
    }

    /** Keyset paging: the cursor walks backwards without an OFFSET scan. */
    @Test
    void theInspector_pagesByCursor() throws Exception {
        String owner = user();
        String project = createProject(owner, "Cards");
        openSandbox(owner, project);
        String key = sandboxAddress(owner, project);
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/s/" + key + "/v1/call" + i));
        }

        String first = mvc.perform(get("/api/v1/projects/" + project + "/requests?limit=2")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long cursor = mapper.readTree(first).get("nextCursor").asLong();

        mvc.perform(get("/api/v1/projects/" + project + "/requests?limit=2&before=" + cursor)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void aStrangerCannotReadTheInspector() throws Exception {
        String project = createProject(user(), "Private");
        mvc.perform(get("/api/v1/projects/" + project + "/requests")
                .header("Authorization", "Bearer " + user())).andExpect(status().isNotFound());
    }

    // --- fixtures ------------------------------------------------------------

    private final java.util.Map<String, String> owners = new java.util.HashMap<>();

    private String userOf(String projectId) {
        return owners.get(projectId);
    }

    private String createProject(String uid, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + uid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"sourceProduct\":\"Acme\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).get("id").asString();
        owners.put(id, uid);
        return id;
    }

    /** Through the console, not SQL — PATCH supports it, so the loop stays SQL-free. */
    private void openSandbox(String uid, String projectId) throws Exception {
        mvc.perform(patch("/api/v1/projects/" + projectId).header("Authorization", "Bearer " + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"authMode\":\"NONE\"}"))
                .andExpect(status().isOk());
    }

    /** The sandbox address is the project's own id — there is no separate key any more. */
    private String sandboxAddress(String uid, String projectId) {
        return projectId;
    }

    private String createCollection(String uid, String projectId, String code) throws Exception {
        String body = mvc.perform(post("/api/v1/projects/" + projectId + "/collections")
                        .header("Authorization", "Bearer " + uid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","recordSchema":{"properties":{"id":{},"status":{},"customerId":{}}}}"""
                                .formatted(code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    private void seed(String uid, String projectId, String collectionId, String json) throws Exception {
        mvc.perform(post("/api/v1/projects/" + projectId + "/collections/" + collectionId + "/records")
                        .header("Authorization", "Bearer " + uid)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    private String createEndpoint(String uid, String projectId, String json) throws Exception {
        String body = mvc.perform(post("/api/v1/projects/" + projectId + "/endpoints")
                        .header("Authorization", "Bearer " + uid)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }
}
