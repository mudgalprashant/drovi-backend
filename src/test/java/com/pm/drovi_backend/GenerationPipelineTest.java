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
 * Phase 3.4 — the pipeline joined up, and the phase's exit criterion as a test.
 *
 * <p>{@link #oneSentence_producesAReadySandboxThatServesGeneratedData} is the criterion
 * verbatim: <em>from one sentence, a project reaches READY with endpoints, schemas and seed
 * data, and its base URL serves them.</em> It starts at an HTTP POST and finishes by calling
 * the sandbox over HTTP, with no SQL in between and no API key anywhere.
 *
 * <p>The stub provider answers by purpose, which is what makes a whole multi-step generation
 * testable offline: RESEARCH, SPEC and SEED each get the reply their step expects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GenerationPipelineTest.StubsConfig.class)
class GenerationPipelineTest extends PostgresTestBase {

    private static final String STUB_KEY_VAR = "DROVI_PIPELINE_STUB_KEY";
    private static final String STUB_MODEL = "pipeline-stub-model";

    private static final String RESEARCH_REPLY = """
            {"product":"Stripe","summary":"Payments.","authentication":{"scheme":"BEARER"},
             "resources":[{"name":"cards","idField":"id","fields":[{"name":"id","type":"string"}]}],
             "endpoints":[{"method":"GET","path":"/v1/cards","resource":"cards","behavior":"LIST"}],
             "confidence":"MEDIUM","uncertainties":["field names are from memory"]}""";

    private static final String SPEC_REPLY = """
            {"projectName":"Stripe cards","authMode":"NONE",
             "collections":[
               {"code":"cards","keyField":"id",
                "recordSchema":{"id":{"type":"string"},"status":{"type":"string"}}},
               {"code":"charges","keyField":"id",
                "recordSchema":{"id":{"type":"string"},"amount":{"type":"integer"}}}],
             "endpoints":[
               {"method":"GET","path":"/v1/cards","behavior":"LIST","collection":"cards"},
               {"method":"GET","path":"/v1/cards/{cardId}","behavior":"GET","collection":"cards",
                "keyParam":"cardId"},
               {"method":"GET","path":"/v1/charges","behavior":"LIST","collection":"charges"}]}""";

    @DynamicPropertySource
    static void providerKey(DynamicPropertyRegistry registry) {
        registry.add(STUB_KEY_VAR, () -> "stub-key-not-a-real-credential");
    }

    /** Answers per purpose, so one stub serves a whole generation. */
    static class StubProvider implements AiProvider {

        final AtomicInteger calls = new AtomicInteger();
        volatile AiPurpose failOn;

        @Override
        public AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request) {
            calls.incrementAndGet();
            AiPurpose purpose = request.purpose();
            if (purpose == failOn) {
                throw com.pm.drovi_backend.ai.AiProviderException.refused("declined");
            }
            return switch (purpose) {
                case RESEARCH -> new AiResponse(RESEARCH_REPLY, 100, 300);
                case SPEC -> new AiResponse(SPEC_REPLY, 200, 500);
                // Ids are derived from the collection so the two seeds cannot collide.
                case SEED -> new AiResponse(seedFor(request.userContent()), 100, 300);
                default -> new AiResponse("{}", 10, 10);
            };
        }

        private static String seedFor(String userContent) {
            String prefix = userContent.contains("COLLECTION: charges") ? "ch" : "card";
            return """
                    {"records":[{"id":"%s_1","status":"ACTIVE","amount":100},
                                {"id":"%s_2","status":"BLOCKED","amount":250}]}"""
                    .formatted(prefix, prefix);
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        StubProvider pipelineStubProvider() {
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

    @BeforeEach
    void setUp() {
        // The REAL pipeline this time — chaining is the thing under test.
        runner = new JobRunner(jobs, config, mapper, pipeline, handlers);
        jdbc.update("DELETE FROM generation_job");

        jdbc.update("""
                INSERT INTO ai_provider_config
                    (code, display_name, adapter_bean, base_url, model, auth_header_name,
                     api_key_env_var, max_output_tokens, active, priority)
                VALUES ('PSTUB','Pipeline stub','pipelineStubProvider','https://stub.invalid',?,
                        'x-stub-key',?, 8192, false, 5)
                ON CONFLICT (code) DO UPDATE
                    SET adapter_bean = EXCLUDED.adapter_bean, api_key_env_var = EXCLUDED.api_key_env_var
                """, STUB_MODEL, STUB_KEY_VAR);
        jdbc.update("""
                INSERT INTO model_pricing (provider_code, model, effective_from,
                                           input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('PSTUB', ?, DATE '2020-01-01', 1000000, 1000000)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL);
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code <> 'PSTUB'");
        jdbc.update("UPDATE ai_provider_config SET active = true WHERE code = 'PSTUB'");

        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "50000000");
        setConfig("ai.account.daily.cost.cap.micros", "5000000");
        setConfig("ai.job.runner.enabled", "true");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), STUB_MODEL);
        }

        provider.calls.set(0);
        provider.failOn = null;
        user = "uid-" + UUID.randomUUID();
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'PSTUB'");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), "gemini-3.7-flash");
        }
    }

    // --- the phase exit criterion --------------------------------------------

    /**
     * <em>From one sentence, a project reaches READY with endpoints, schemas and seed data, and
     * its base URL serves them.</em>
     *
     * <p>Every step here is HTTP or the runner's own tick. Nothing in this test writes an
     * endpoint, a collection or a record.
     */
    @Test
    void oneSentence_producesAReadySandboxThatServesGeneratedData() throws Exception {
        String project = createProject();

        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe's card API\",\"agentResearchOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // While it runs, the sandbox does not serve: routes and data arrive together.
        assertThat(projectStatus(project)).isEqualTo("GENERATING");

        drainTheQueue();

        assertThat(projectStatus(project)).isEqualTo("READY");
        assertThat(provider.calls).as("research, spec, and one seed per collection").hasValue(4);

        String key = projectKey(project);
        mvc.perform(get("/s/" + key + "/v1/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/s/" + key + "/v1/cards/card_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("card_1"));
        mvc.perform(get("/s/" + key + "/v1/charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // --- the chain ------------------------------------------------------------

    @Test
    void afterResearch_specIsEnqueuedAgainstTheResearchThatProducedIt() throws Exception {
        String project = createProject();
        startGeneration(project);

        runner.claimAndRunOne();

        assertThat(kindsWithStatus("QUEUED")).containsExactly("SPEC");
        assertThat(jdbc.queryForObject(
                "SELECT input::text FROM generation_job WHERE kind = 'SPEC'", String.class))
                .contains("researchJobId");
    }

    /** One SEED job per collection, because that is how the runner's cadence paces the calls. */
    @Test
    void afterSpec_oneSeedJobIsEnqueuedPerCollection() throws Exception {
        String project = createProject();
        startGeneration(project);
        runner.claimAndRunOne();
        runner.claimAndRunOne();

        assertThat(kindsWithStatus("QUEUED")).containsExactly("SEED", "SEED");
    }

    /**
     * The project becomes READY only when nothing is left outstanding — not when the first seed
     * finishes. Otherwise a sandbox goes live with one of its collections still empty.
     */
    @Test
    void theProjectBecomesReadyOnlyAfterTheLastSeedFinishes() throws Exception {
        String project = createProject();
        startGeneration(project);
        runner.claimAndRunOne();
        runner.claimAndRunOne();

        runner.claimAndRunOne();
        assertThat(projectStatus(project)).as("one collection still unseeded").isEqualTo("GENERATING");

        runner.claimAndRunOne();
        assertThat(projectStatus(project)).isEqualTo("READY");
    }

    /**
     * A generation that stops has to leave a project that says so. A project that merely never
     * becomes ready is indistinguishable from one still working, forever.
     */
    @Test
    void whenAStepFailsTerminally_theProjectSaysItFailedRatherThanStayingSilent() throws Exception {
        String project = createProject();
        provider.failOn = AiPurpose.SPEC;
        startGeneration(project);

        drainTheQueue();

        assertThat(projectStatus(project)).isEqualTo("FAILED");
        assertThat(kindsWithStatus("FAILED")).containsExactly("SPEC");
    }

    /** A failure partway leaves no half-built sandbox behind it. */
    @Test
    void whenSpecFails_theProjectHasNoRoutesAtAll() throws Exception {
        String project = createProject();
        provider.failOn = AiPurpose.SPEC;
        startGeneration(project);

        drainTheQueue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_endpoint WHERE project_id = ?::uuid",
                Long.class, project)).isZero();
    }

    // --- starting one ---------------------------------------------------------

    /** Two generations into one project would race to write the same routes. */
    @Test
    void start_whileOneIsAlreadyRunning_isRefused() throws Exception {
        String project = createProject();
        startGeneration(project);

        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"agentResearchOnly\":true}"))
                .andExpect(status().isConflict());
    }

    /**
     * SPEC refuses a built project too, but only after research has been paid for. Failing at
     * the HTTP call is the difference between a clear 409 and a surprise on the bill.
     */
    @Test
    void start_intoAProjectThatAlreadyHasRoutes_isRefusedBeforeAnythingIsSpent() throws Exception {
        String project = createProject();
        startGeneration(project);
        drainTheQueue();
        int spentSoFar = provider.calls.get();

        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"agentResearchOnly\":true}"))
                .andExpect(status().isConflict());

        assertThat(provider.calls).hasValue(spentSoFar);
    }

    /** Decision M reaches the HTTP surface: no docs and no opt-in is an unfinished request. */
    @Test
    void start_withNeitherDocsNorAnOptIn_failsTheJobAskingForOne() throws Exception {
        String project = createProject();

        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\"}"))
                .andExpect(status().isAccepted());
        drainTheQueue();

        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM generation_job WHERE kind = 'RESEARCH'", String.class))
                .isEqualTo("RESEARCH_INPUT_MISSING");
        assertThat(projectStatus(project)).isEqualTo("FAILED");
    }

    @Test
    void history_showsEveryStepOfTheGeneration() throws Exception {
        String project = createProject();
        startGeneration(project);
        drainTheQueue();

        mvc.perform(get("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("SUCCEEDED"))));
    }

    /** A stranger's project is indistinguishable from one that does not exist. */
    @Test
    void start_onSomebodyElsesProject_isNotFound() throws Exception {
        String project = createProject();
        String stranger = "uid-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"agentResearchOnly\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void start_withoutAToken_isRejected() throws Exception {
        String project = createProject();

        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"agentResearchOnly\":true}"))
                .andExpect(status().isUnauthorized());
    }

    // --- fixtures -------------------------------------------------------------

    /** Runs the queue to a standstill, the way the scheduler would over a few minutes. */
    private void drainTheQueue() {
        for (int tick = 0; tick < 20 && runner.claimAndRunOne(); tick++) {
            // each tick claims at most one job, exactly as the poller does
        }
    }

    private String createProject() throws Exception {
        String body = mvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Payments\",\"sourceProduct\":\"Stripe\",\"authMode\":\"NONE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    private void startGeneration(String project) throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"agentResearchOnly\":true}"))
                .andExpect(status().isAccepted());
    }

    private String projectKey(String project) throws Exception {
        String body = mvc.perform(get("/api/v1/projects/" + project)
                        .header("Authorization", "Bearer " + user))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("projectKey").asString();
    }

    private String projectStatus(String project) {
        return jdbc.queryForObject("SELECT status FROM sandbox_project WHERE id = ?::uuid",
                String.class, project);
    }

    private List<String> kindsWithStatus(String status) {
        return jdbc.queryForList("SELECT kind FROM generation_job WHERE status = ? ORDER BY kind",
                String.class, status);
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by GenerationPipelineTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
