package com.pm.drovi_backend;

import com.pm.drovi_backend.ai.AiProvider;
import com.pm.drovi_backend.ai.AiPurpose;
import com.pm.drovi_backend.ai.AiRequest;
import com.pm.drovi_backend.ai.AiResponse;
import com.pm.drovi_backend.ai.ProviderConfig;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.generation.JobChain;
import com.pm.drovi_backend.generation.JobHandler;
import com.pm.drovi_backend.generation.JobRunner;
import com.pm.drovi_backend.generation.JobStore;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pasting a specification, instead of describing a product.
 *
 * <p>When the user supplies the actual document, research is not merely unnecessary — it is
 * strictly worse. A model recalling an API produces something close; the spec <em>is</em> the
 * API. So the structure is read directly and no model is asked about it at all.
 *
 * <p>The stub provider here <strong>throws</strong> if asked for RESEARCH or SPEC. That is the
 * assertion: not "the import worked" but "nothing was spent working out what the import already
 * said". Only SEED is allowed through, because a spec says what a field is and not what a
 * plausible value looks like.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SpecImportTest.StubsConfig.class)
class SpecImportTest extends PostgresTestBase {

    private static final String STUB_KEY_VAR = "DROVI_IMPORT_STUB_KEY";
    private static final String STUB_MODEL = "import-stub-model";

    private static final String OPENAPI = """
            {"openapi":"3.0.3",
             "info":{"title":"Cards API","version":"1.0"},
             "components":{
               "securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer"}},
               "schemas":{"Card":{"type":"object","properties":{
                   "id":{"type":"string"},"last4":{"type":"string"},"status":{"type":"string"}}}}},
             "paths":{
               "/v1/cards":{
                 "get":{"tags":["Cards"],"summary":"List cards"},
                 "post":{"tags":["Cards"],"summary":"Create a card"}},
               "/v1/cards/{cardId}":{
                 "get":{"tags":["Cards"],"summary":"Fetch a card"},
                 "delete":{"tags":["Cards"],"summary":"Delete a card"}}}}""";

    private static final String POSTMAN = """
            {"info":{"name":"Payments","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "item":[
               {"name":"Charges",
                "item":[
                  {"name":"List charges",
                   "request":{"method":"GET","url":{"path":["v1","charges"]}}},
                  {"name":"Fetch a charge",
                   "request":{"method":"GET","url":{"path":["v1","charges",":chargeId"]}}}]}]}""";

    @DynamicPropertySource
    static void providerKey(DynamicPropertyRegistry registry) {
        registry.add(STUB_KEY_VAR, () -> "stub-key-not-a-real-credential");
    }

    /** Allows SEED. Anything else is a failure of the thing under test. */
    static class StubProvider implements AiProvider {

        final AtomicInteger seedCalls = new AtomicInteger();

        @Override
        public AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request) {
            if (request.purpose() != AiPurpose.SEED) {
                throw new AssertionError(
                        "an imported specification must not cost a " + request.purpose() + " call");
            }
            seedCalls.incrementAndGet();
            return new AiResponse("{\"records\":[{\"id\":\"rec_1\",\"status\":\"ACTIVE\"}]}", 50, 100);
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        StubProvider importStubProvider() {
            return new StubProvider();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").subject(token)
                    .claim("email", token + "@example.test")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        }
    }

    @Autowired
    JobStore jobs;
    @Autowired
    JobChain pipeline;
    @Autowired
    List<JobHandler> handlers;
    @Autowired
    AppConfigService config;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    StubProvider provider;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    private JobRunner runner;
    private String user;
    private String project;

    @BeforeEach
    void setUp() throws Exception {
        runner = new JobRunner(jobs, config, mapper, pipeline, handlers);
        jdbc.update("DELETE FROM generation_clarification");
        jdbc.update("DELETE FROM generation_job");

        jdbc.update("""
                INSERT INTO ai_provider_config
                    (code, display_name, adapter_bean, base_url, model, auth_header_name,
                     api_key_env_var, max_output_tokens, active, priority)
                VALUES ('ISTUB','Import stub','importStubProvider','https://stub.invalid',?,
                        'x-stub-key',?, 8192, false, 8)
                ON CONFLICT (code) DO UPDATE
                    SET adapter_bean = EXCLUDED.adapter_bean, api_key_env_var = EXCLUDED.api_key_env_var
                """, STUB_MODEL, STUB_KEY_VAR);
        jdbc.update("""
                INSERT INTO model_pricing (provider_code, model, effective_from,
                                           input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('ISTUB', ?, DATE '2020-01-01', 1000000, 1000000)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL);
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code <> 'ISTUB'");
        jdbc.update("UPDATE ai_provider_config SET active = true WHERE code = 'ISTUB'");

        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "50000000");
        setConfig("ai.account.daily.cost.cap.micros", "5000000");
        setConfig("ai.job.runner.enabled", "true");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), STUB_MODEL);
        }

        provider.seedCalls.set(0);
        user = "uid-" + UUID.randomUUID();
        project = createProject();
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'ISTUB'");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), "gemini-3.7-flash");
        }
    }

    // --- OpenAPI --------------------------------------------------------------

    /** The whole point: the document becomes a serving sandbox, and nothing is asked about it. */
    @Test
    void anOpenApiDocument_becomesAServingSandboxWithNoResearch() throws Exception {
        startWith(OPENAPI);
        drain();

        assertThat(kinds()).as("no RESEARCH step at all").containsExactlyInAnyOrder("SPEC", "SEED");
        assertThat(routes()).containsExactlyInAnyOrder(
                "GET /v1/cards", "POST /v1/cards", "GET /v1/cards/{cardId}", "DELETE /v1/cards/{cardId}");
        assertThat(projectStatus()).isEqualTo("READY");

        jdbc.update("UPDATE sandbox_project SET auth_mode = 'NONE' WHERE id = ?::uuid", project);
        mvc.perform(get("/s/" + project + "/v1/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** Paths are the document's, verbatim — the same promise research has to keep. */
    @Test
    void theRoutes_areExactlyWhatTheDocumentDeclared() throws Exception {
        startWith(OPENAPI);
        drain();

        assertThat(routes()).allSatisfy(route -> assertThat(route).startsWith(route.split(" ")[0] + " /v1/"));
    }

    /**
     * A spec says what a route is, not what it does with data. GET on a path ending in a
     * parameter is one record; on the bare path it is many.
     */
    @Test
    void behaviours_areInferredFromTheMethodAndTheShapeOfThePath() throws Exception {
        startWith(OPENAPI);
        drain();

        assertThat(behaviourOf("GET", "/v1/cards")).isEqualTo("LIST");
        assertThat(behaviourOf("GET", "/v1/cards/{cardId}")).isEqualTo("GET");
        assertThat(behaviourOf("POST", "/v1/cards")).isEqualTo("CREATE");
        assertThat(behaviourOf("DELETE", "/v1/cards/{cardId}")).isEqualTo("DELETE");
    }

    /** The component schema is what gives the seeder real field names instead of invented ones. */
    @Test
    void theComponentSchema_becomesTheCollectionsRecordShape() throws Exception {
        startWith(OPENAPI);
        drain();

        assertThat(jdbc.queryForObject("""
                SELECT record_schema::text FROM sandbox_collection WHERE project_id = ?::uuid
                """, String.class, project)).contains("last4").contains("status");
    }

    /** A replica that waves everything through never exercises the caller's auth path. */
    @Test
    void theSecurityScheme_becomesTheSandboxsAuthMode() throws Exception {
        startWith(OPENAPI);
        drain();

        assertThat(jdbc.queryForObject("SELECT auth_mode FROM sandbox_project WHERE id = ?::uuid",
                String.class, project)).isEqualTo("BEARER");
    }

    /**
     * Real specs nest. {@code /v1/customers/{customerId}/cards/{cardId}} addresses a card, and
     * inference alone cannot tell which of two parameters that is — so the importer names it.
     */
    @Test
    void aNestedPath_namesTheLastParameterAsTheRecordKey() throws Exception {
        startWith("""
                {"openapi":"3.0.3","info":{"title":"Nested"},
                 "paths":{"/v1/customers/{customerId}/cards/{cardId}":{"get":{"summary":"Fetch"}}}}""");
        drain();

        assertThat(jdbc.queryForObject("""
                SELECT key_param FROM api_endpoint WHERE project_id = ?::uuid
                """, String.class, project)).isEqualTo("cardId");
    }

    // --- Postman --------------------------------------------------------------

    @Test
    void aPostmanCollection_becomesAServingSandbox() throws Exception {
        startWith(POSTMAN);
        drain();

        assertThat(kinds()).containsExactlyInAnyOrder("SPEC", "SEED");
        assertThat(routes()).containsExactlyInAnyOrder("GET /v1/charges", "GET /v1/charges/{chargeId}");
    }

    /** Postman marks variables with a colon; ours uses braces. Nothing else is rewritten. */
    @Test
    void postmanPathVariables_areTranslatedAndNothingElseIsTouched() throws Exception {
        startWith(POSTMAN);
        drain();

        assertThat(routes()).contains("GET /v1/charges/{chargeId}");
    }

    /** Folder names are the natural grouping a user already organised their collection by. */
    @Test
    void postmanFolders_becomeApiGroups() throws Exception {
        startWith(POSTMAN);
        drain();

        assertThat(jdbc.queryForList("SELECT name FROM api_collection WHERE project_id = ?::uuid",
                String.class, project)).containsExactly("Charges");
    }

    // --- falling back --------------------------------------------------------

    /**
     * A paste that is not a spec must cost nothing but the normal path. Prose, YAML and a
     * half-written document all land here.
     */
    @Test
    void proseIsNotASpec_soTheNormalResearchPathIsUsed() throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"docs\":\"The cards API returns a card object.\"}"))
                .andExpect(status().isAccepted());

        assertThat(kinds()).containsExactly("RESEARCH");
    }

    /** YAML is the common way to pass an OpenAPI file, and it is honestly not supported. */
    @Test
    void yaml_fallsBackRatherThanBeingHalfParsed() throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Cards\",\"docs\":\"openapi: 3.0.3\\npaths:\\n  /v1/cards:\\n    get: {}\"}"))
                .andExpect(status().isAccepted());

        assertThat(kinds()).containsExactly("RESEARCH");
    }

    /** An OpenAPI document with no paths describes no sandbox. */
    @Test
    void anOpenApiDocumentWithNoPaths_fallsBack() throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Cards\",\"docs\":\"{\\\"openapi\\\":\\\"3.0.3\\\",\\\"paths\\\":{}}\"}"))
                .andExpect(status().isAccepted());

        assertThat(kinds()).containsExactly("RESEARCH");
    }

    // --- fixtures -------------------------------------------------------------

    private void startWith(String document) throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                java.util.Map.of("product", "Cards", "docs", document))))
                .andExpect(status().isAccepted());
    }

    private void drain() {
        for (int tick = 0; tick < 20 && runner.claimAndRunOne(); tick++) {
            // one job per tick, exactly as the poller does
        }
    }

    private List<String> kinds() {
        return jdbc.queryForList("SELECT kind FROM generation_job WHERE project_id = ?::uuid ORDER BY kind",
                String.class, project);
    }

    private List<String> routes() {
        return jdbc.queryForList("""
                SELECT method || ' ' || path_template FROM api_endpoint WHERE project_id = ?::uuid
                """, String.class, project);
    }

    private String behaviourOf(String method, String path) {
        return jdbc.queryForObject("""
                SELECT behavior FROM api_endpoint
                 WHERE project_id = ?::uuid AND method = ? AND path_template = ?
                """, String.class, project, method, path);
    }

    private String projectStatus() {
        return jdbc.queryForObject("SELECT status FROM sandbox_project WHERE id = ?::uuid",
                String.class, project);
    }

    private String createProject() throws Exception {
        String body = mvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cards\",\"sourceProduct\":\"Cards API\",\"authMode\":\"NONE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by SpecImportTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
