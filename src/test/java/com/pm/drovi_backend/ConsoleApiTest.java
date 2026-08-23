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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The console API, driven over HTTP the way the web console will.
 *
 * <p>Two of these matter more than the rest: that one account cannot reach another's
 * project, and that data created through the console is actually served by the sandbox.
 * The first is the one unrecoverable bug in this system; the second is the point of the
 * whole phase.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConsoleApiTest.StubFirebase.class)
class ConsoleApiTest extends PostgresTestBase {

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

    // --- projects ------------------------------------------------------------

    @Test
    void createProject_returnsTheBaseUrlTheUserCameFor() throws Exception {
        String body = mvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cards sandbox","sourceProduct":"Acme Cards"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.authMode").value("BEARER"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        assertThat(json.get("projectKey").asString()).isNotBlank();
        assertThat(json.get("baseUrl").asString())
                .as("the base URL is the artifact — it must end with /s/<projectKey>")
                .endsWith("/s/" + json.get("projectKey").asString());
    }

    /**
     * The one unrecoverable bug in a multi-tenant store. A project id is a UUID, so this is
     * not about guessing it — it is about what happens when someone does.
     */
    @Test
    void anotherAccount_cannotSeeOrTouchTheProject() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Private");
        String stranger = user();

        mvc.perform(get("/api/v1/projects/" + projectId).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mvc.perform(patch("/api/v1/projects/" + projectId).header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"stolen\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/projects/" + projectId).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/projects/" + projectId + "/keys")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    /** 404 rather than 403, so the API cannot be used to discover which ids exist. */
    @Test
    void aStrangersProject_looksIdenticalToOneThatDoesNotExist() throws Exception {
        String stranger = user();
        String real = createProject(user(), "Private");

        String realResponse = mvc.perform(get("/api/v1/projects/" + real)
                        .header("Authorization", "Bearer " + stranger))
                .andReturn().getResponse().getContentAsString();
        String imaginary = mvc.perform(get("/api/v1/projects/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + stranger))
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(realResponse).get("error").get("code").asString())
                .isEqualTo(mapper.readTree(imaginary).get("error").get("code").asString());
    }

    @Test
    void projectsAreListedForTheirOwnerOnly() throws Exception {
        String owner = user();
        createProject(owner, "One");
        createProject(owner, "Two");
        createProject(user(), "Someone else's");

        mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** The free tier allows two. The third is a quota answer, not a permissions one. */
    @Test
    void exceedingThePlansProjectLimit_isRefused() throws Exception {
        String owner = user();
        createProject(owner, "One");
        createProject(owner, "Two");

        mvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Three\",\"sourceProduct\":\"Acme\"}"))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"));
    }

    /** Archiving frees a slot without destroying anything. */
    @Test
    void archiving_freesAPlanSlot_andStopsTheSandboxServing() throws Exception {
        String owner = user();
        String first = createProject(owner, "One");
        createProject(owner, "Two");
        String key = projectKeyOf(owner, first);

        mvc.perform(delete("/api/v1/projects/" + first).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());

        mvc.perform(get("/s/" + key + "/anything"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SANDBOX_NOT_FOUND"));

        mvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Three\",\"sourceProduct\":\"Acme\"}"))
                .andExpect(status().isCreated());
    }

    // --- API keys ------------------------------------------------------------

    /** The issued key must actually open the sandbox, and must never be shown twice. */
    @Test
    void anIssuedKey_opensTheSandbox_andIsNeverShownAgain() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String projectKey = projectKeyOf(owner, projectId);

        String issued = mvc.perform(post("/api/v1/projects/" + projectId + "/keys")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CI\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String rawKey = mapper.readTree(issued).get("key").asString();

        // Listing must expose the prefix and never the key.
        String listed = mvc.perform(get("/api/v1/projects/" + projectId + "/keys")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(rawKey);

        // The project defaults to BEARER, so the sandbox must demand this key.
        mvc.perform(get("/s/" + projectKey + "/v1/anything"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/s/" + projectKey + "/v1/anything").header("Authorization", "Bearer " + rawKey))
                .andExpect(status().isNotFound())   // authenticated; simply no such route
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        // And the raw key is nowhere in the database.
        Long stored = jdbc.queryForObject(
                "SELECT count(*) FROM project_api_key WHERE key_hash = ?", Long.class, rawKey);
        assertThat(stored).as("only the hash is stored").isZero();
    }

    @Test
    void revokingAKey_closesTheSandboxToIt() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String projectKey = projectKeyOf(owner, projectId);

        String issued = mvc.perform(post("/api/v1/projects/" + projectId + "/keys")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(issued);
        String rawKey = json.get("key").asString();

        mvc.perform(get("/s/" + projectKey + "/v1/x").header("Authorization", "Bearer " + rawKey))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/projects/" + projectId + "/keys/" + json.get("id").asString())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());

        mvc.perform(get("/s/" + projectKey + "/v1/x").header("Authorization", "Bearer " + rawKey))
                .andExpect(status().isUnauthorized());
    }

    // --- data ----------------------------------------------------------------

    /**
     * The point of the phase: seed data through the console, and the sandbox serves it.
     *
     * <p>The endpoint is still inserted by SQL because endpoint management is Phase 2.4 —
     * that gap is exactly what this test makes visible.
     */
    @Test
    void dataSeededThroughTheConsole_isServedByTheSandbox() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String projectKey = projectKeyOf(owner, projectId);
        openSandbox(projectId);

        String collectionId = createCollection(owner, projectId, "cards");

        mvc.perform(post("/api/v1/projects/" + projectId + "/collections/" + collectionId + "/records")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[
                                  {"id":"card_1","status":"BLOCKED","customerId":"cus_1"},
                                  {"id":"card_2","status":"ACTIVE","customerId":"cus_2"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));

        wireGetEndpoint(projectId, collectionId);

        mvc.perform(get("/s/" + projectKey + "/v1/cards/card_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.customerId").value("cus_1"));
    }

    @Test
    void records_canBeListedUpdatedAndDeleted() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String collectionId = createCollection(owner, projectId, "cards");
        String base = "/api/v1/projects/" + projectId + "/collections/" + collectionId + "/records";

        mvc.perform(post(base).header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"record\":{\"id\":\"card_9\",\"status\":\"ACTIVE\"}}"))
                .andExpect(status().isCreated());

        mvc.perform(get(base).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].recordKey").value("card_9"));

        mvc.perform(patch(base + "/card_9").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\",\"id\":\"card_HIJACK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                // A patch body must not be able to re-identify an existing record.
                .andExpect(jsonPath("$.data.id").value("card_9"));

        mvc.perform(delete(base + "/card_9").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mvc.perform(get(base + "/card_9").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateRecordKey_isAConflict() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String collectionId = createCollection(owner, projectId, "cards");
        String base = "/api/v1/projects/" + projectId + "/collections/" + collectionId + "/records";
        String payload = "{\"record\":{\"id\":\"dup\",\"v\":1}}";

        mvc.perform(post(base).header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated());
        mvc.perform(post(base).header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    /**
     * Quota is checked once for the whole batch, before anything is written. A partially
     * applied seed would leave the project over its limit and the user with a collection
     * they cannot reason about.
     */
    @Test
    void aBulkSeedThatExceedsQuota_writesNothing() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String collectionId = createCollection(owner, projectId, "cards");
        putOnTinyPlan(owner);

        mvc.perform(post("/api/v1/projects/" + projectId + "/collections/" + collectionId + "/records")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"records":[{"id":"a"},{"id":"b"},{"id":"c"},{"id":"d"},{"id":"e"}]}"""))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"));

        Long written = jdbc.queryForObject(
                "SELECT count(*) FROM sandbox_record WHERE collection_id = ?::uuid", Long.class, collectionId);
        assertThat(written).as("an over-quota batch must be all-or-nothing").isZero();
    }

    @Test
    void aStrangerCannotReachAnotherProjectsData() throws Exception {
        String owner = user();
        String projectId = createProject(owner, "Cards");
        String collectionId = createCollection(owner, projectId, "cards");

        mvc.perform(get("/api/v1/projects/" + projectId + "/collections/" + collectionId + "/records")
                        .header("Authorization", "Bearer " + user()))
                .andExpect(status().isNotFound());
    }

    // --- fixtures ------------------------------------------------------------

    private String createProject(String uid, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + uid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"sourceProduct\":\"Acme\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    private String projectKeyOf(String uid, String projectId) throws Exception {
        String body = mvc.perform(get("/api/v1/projects/" + projectId)
                .header("Authorization", "Bearer " + uid)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("projectKey").asString();
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

    /** Drops sandbox auth so a test can call the base URL without carrying a key. */
    private void openSandbox(String projectId) {
        jdbc.update("UPDATE sandbox_project SET auth_mode = 'NONE' WHERE id = ?::uuid", projectId);
    }

    /** Endpoint management is Phase 2.4; until then a route is wired by SQL. */
    private void wireGetEndpoint(String projectId, String collectionId) {
        UUID apiCollection = jdbc.queryForObject("""
                INSERT INTO api_collection (project_id, name) VALUES (?::uuid, 'Cards') RETURNING id
                """, UUID.class, projectId);
        jdbc.update("""
                INSERT INTO api_endpoint (project_id, collection_id, method, path_template, summary,
                                          behavior, data_collection_id, key_param, response_template)
                VALUES (?::uuid, ?, 'GET', '/v1/cards/{cardId}', 'Get card', 'GET', ?::uuid, 'cardId', '{}'::jsonb)
                """, projectId, apiCollection, collectionId);
    }

    /** A plan with room for three records, so quota can be exceeded without bulk inserts. */
    private void putOnTinyPlan(String uid) {
        jdbc.update("""
                INSERT INTO plan_catalog (code, display_name, max_projects, max_endpoints_per_project,
                    max_records_per_project, max_stored_bytes_per_project, max_mock_requests_per_month,
                    max_ai_tokens_per_month, max_generations_per_month)
                VALUES ('TEST_TINY','Tiny',5,10,3,1048576,100,100,1)
                ON CONFLICT (code) DO NOTHING
                """);
        jdbc.update("UPDATE accounts SET plan_code = 'TEST_TINY' WHERE firebase_uid = ?", uid);
    }
}
