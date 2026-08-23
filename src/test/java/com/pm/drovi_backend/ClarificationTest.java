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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Doubts: the system asking rather than guessing, and the generation waiting until it is told.
 *
 * <p>"Give me a blocked card" has several readings when three endpoints serve cards and a card
 * carries both {@code status} and {@code blocked}. Picking one silently produces a sandbox that
 * <em>looks</em> right — so the user builds against it and finds out later. These tests are
 * about the system declining to do that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClarificationTest.StubsConfig.class)
class ClarificationTest extends PostgresTestBase {

    private static final String STUB_KEY_VAR = "DROVI_CLARIFY_STUB_KEY";
    private static final String STUB_MODEL = "clarify-stub-model";

    /** Research that is not sure which field means "blocked". */
    private static final String RESEARCH_WITH_DOUBT = """
            {"product":"Stripe","summary":"Payments.",
             "resources":[{"name":"cards","idField":"id","fields":[{"name":"id","type":"string"}]}],
             "endpoints":[{"method":"GET","path":"/v1/cards","resource":"cards","behavior":"LIST"}],
             "confidence":"MEDIUM",
             "questions":[
               {"question":"Which field marks a card as blocked?",
                "detail":"A card carries both status and blocked, and they are not the same thing.",
                "subject":{"resource":"cards","field":"status"},
                "options":[{"label":"status = BLOCKED","detail":"The lifecycle field."},
                           {"label":"blocked = true","detail":"A separate flag."}],
                "allowsAssumption":true}]}""";

    private static final String RESEARCH_WITHOUT_DOUBT = """
            {"product":"Stripe","summary":"Payments.",
             "resources":[{"name":"cards","idField":"id","fields":[{"name":"id","type":"string"}]}],
             "endpoints":[{"method":"GET","path":"/v1/cards","resource":"cards","behavior":"LIST"}],
             "confidence":"HIGH"}""";

    private static final String SPEC_REPLY = """
            {"projectName":"Cards","authMode":"NONE",
             "collections":[{"code":"cards","keyField":"id","recordSchema":{"id":{"type":"string"}}}],
             "endpoints":[{"method":"GET","path":"/v1/cards","behavior":"LIST","collection":"cards"}]}""";

    @DynamicPropertySource
    static void providerKey(DynamicPropertyRegistry registry) {
        registry.add(STUB_KEY_VAR, () -> "stub-key-not-a-real-credential");
    }

    static class StubProvider implements AiProvider {
        final AtomicReference<AiRequest> lastRequest = new AtomicReference<>();
        volatile String researchReply = RESEARCH_WITH_DOUBT;

        @Override
        public AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request) {
            lastRequest.set(request);
            return switch (request.purpose()) {
                case RESEARCH -> new AiResponse(researchReply, 100, 200);
                case SPEC -> new AiResponse(SPEC_REPLY, 100, 200);
                case SEED -> new AiResponse("{\"records\":[{\"id\":\"card_1\"}]}", 50, 100);
                default -> new AiResponse("{}", 10, 10);
            };
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        StubProvider clarifyStubProvider() {
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
                VALUES ('CSTUB','Clarify stub','clarifyStubProvider','https://stub.invalid',?,
                        'x-stub-key',?, 8192, false, 6)
                ON CONFLICT (code) DO UPDATE
                    SET adapter_bean = EXCLUDED.adapter_bean, api_key_env_var = EXCLUDED.api_key_env_var
                """, STUB_MODEL, STUB_KEY_VAR);
        jdbc.update("""
                INSERT INTO model_pricing (provider_code, model, effective_from,
                                           input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('CSTUB', ?, DATE '2020-01-01', 1000000, 1000000)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL);
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code <> 'CSTUB'");
        jdbc.update("UPDATE ai_provider_config SET active = true WHERE code = 'CSTUB'");

        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "50000000");
        setConfig("ai.account.daily.cost.cap.micros", "5000000");
        setConfig("ai.job.runner.enabled", "true");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), STUB_MODEL);
        }
        setConfig("ai.job.estimated.seconds.per.step", "45");
        setConfig("ai.generation.expected.collections", "3");

        provider.researchReply = RESEARCH_WITH_DOUBT;
        user = "uid-" + UUID.randomUUID();
        project = createProject();
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'CSTUB'");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), "gemini-3.7-flash");
        }
    }

    // --- asking rather than guessing -----------------------------------------

    /**
     * The whole point. A step that is unsure must stop, not pick — a silently chosen reading
     * produces a sandbox that looks right and is wrong.
     */
    @Test
    void whenResearchIsUnsure_generationStopsAndTheQuestionIsAsked() throws Exception {
        startGeneration();

        runner.claimAndRunOne();

        mvc.perform(get("/api/v1/projects/" + project + "/clarifications")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].question").value("Which field marks a card as blocked?"))
                .andExpect(jsonPath("$[0].options.length()").value(2));

        assertThat(queuedKinds()).as("nothing proceeds while a doubt is open").isEmpty();
        assertThat(projectStatus()).isEqualTo("GENERATING");
    }

    /** Concrete options, because someone shown a blank box does not answer at all. */
    @Test
    void aQuestion_carriesItsContextAndItsOptions() throws Exception {
        startGeneration();
        runner.claimAndRunOne();

        mvc.perform(get("/api/v1/projects/" + project + "/clarifications")
                        .header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$[0].detail").value(
                        org.hamcrest.Matchers.containsString("status and blocked")))
                .andExpect(jsonPath("$[0].subject.resource").value("cards"))
                .andExpect(jsonPath("$[0].options[0].label").value("status = BLOCKED"))
                .andExpect(jsonPath("$[0].allowsAssumption").value(true));
    }

    @Test
    void withNoAmbiguity_nothingIsAskedAndTheChainCarriesOn() throws Exception {
        provider.researchReply = RESEARCH_WITHOUT_DOUBT;
        startGeneration();

        runner.claimAndRunOne();

        assertThat(clarificationCount()).isZero();
        assertThat(queuedKinds()).containsExactly("SPEC");
    }

    // --- answering ------------------------------------------------------------

    /** Answering the last open question is what restarts the generation. */
    @Test
    void answeringTheLastQuestion_resumesTheGeneration() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        String doubt = firstClarificationId();

        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + doubt + "/answer")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"opt1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("status = BLOCKED"));

        assertThat(queuedKinds()).containsExactly("SPEC");
    }

    /** The next step is told what was decided, or it asks the same thing again. */
    @Test
    void theAnswer_isCarriedIntoTheNextStep() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        answerFirst("{\"optionId\":\"opt1\"}");

        runner.claimAndRunOne();

        assertThat(provider.lastRequest.get().userContent()).contains("status = BLOCKED");
    }

    @Test
    void aFreeTextAnswer_isAcceptedWhenNoOptionFits() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        String doubt = firstClarificationId();

        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + doubt + "/answer")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"neither — use state = FROZEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("neither — use state = FROZEN"));
    }

    /**
     * "You decide" is a real answer, not a way of skipping. This is a mock, and a user who does
     * not care should not be made to care.
     */
    @Test
    void assuming_isAnAnswerAndItsDecisionIsRecorded() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        String doubt = firstClarificationId();

        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + doubt + "/assume")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSUMED"))
                // An assumption nobody can look up later is indistinguishable from a bug.
                .andExpect(jsonPath("$.answer").value(
                        org.hamcrest.Matchers.containsString("status = BLOCKED")));

        assertThat(queuedKinds()).containsExactly("SPEC");
    }

    /** Answered questions are kept. The history is the feature. */
    @Test
    void answeredQuestions_arePreservedRatherThanCleared() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        answerFirst("{\"optionId\":\"opt2\"}");

        mvc.perform(get("/api/v1/projects/" + project + "/clarifications")
                        .header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$[0].answer").value("blocked = true"))
                .andExpect(jsonPath("$[0].answeredAt").isNotEmpty());
    }

    @Test
    void answeringTwice_isAConflictRatherThanASecondResume() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        String doubt = firstClarificationId();
        answerFirst("{\"optionId\":\"opt1\"}");

        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + doubt + "/answer")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"opt2\"}"))
                .andExpect(status().isConflict());

        assertThat(queuedKinds()).as("the chain must not be started twice").containsExactly("SPEC");
    }

    @Test
    void anEmptyAnswer_isRejected() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        String doubt = firstClarificationId();

        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + doubt + "/answer")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Somebody else's doubt is indistinguishable from one that does not exist. */
    @Test
    void anotherTenantsQuestion_isNotFound() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        String doubt = firstClarificationId();
        String stranger = "uid-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + doubt + "/assume")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    // --- the whole loop -------------------------------------------------------

    /** Ask, answer, and the sandbox finishes building and serves. */
    @Test
    void afterEveryDoubtIsCleared_theSandboxFinishesAndServes() throws Exception {
        startGeneration();
        runner.claimAndRunOne();
        answerFirst("{\"optionId\":\"opt1\"}");

        for (int tick = 0; tick < 10 && runner.claimAndRunOne(); tick++) {
            // research is already done; spec then one seed
        }

        assertThat(projectStatus()).isEqualTo("READY");
        mvc.perform(get("/s/" + projectKey() + "/v1/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // --- how long to wait -----------------------------------------------------

    /** "About three minutes" is something a person can plan around. "A few" is not. */
    @Test
    void progress_givesAWaitTimeInSecondsAndInWords() throws Exception {
        startGeneration();

        mvc.perform(get("/api/v1/projects/" + project + "/generations/progress")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingForYou").value(false))
                .andExpect(jsonPath("$.estimatedSeconds").value(180))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("about 3 minutes")));
    }

    /**
     * While a doubt is open the clock is not running, so there is no estimate to give.
     * Counting down to nothing would be worse than saying nothing.
     */
    @Test
    void progress_whileWaitingOnTheUser_offersNoEstimateAndSaysWhy() throws Exception {
        startGeneration();
        runner.claimAndRunOne();

        mvc.perform(get("/api/v1/projects/" + project + "/generations/progress")
                        .header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.waitingForYou").value(true))
                .andExpect(jsonPath("$.openQuestions").value(1))
                .andExpect(jsonPath("$.estimatedSeconds").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("needs your answer")));
    }

    /** The estimate gets more truthful once the real collection count is known. */
    @Test
    void progress_afterTheSpec_countsTheRealStepsRatherThanAssumingThem() throws Exception {
        provider.researchReply = RESEARCH_WITHOUT_DOUBT;
        startGeneration();
        runner.claimAndRunOne();
        runner.claimAndRunOne();

        mvc.perform(get("/api/v1/projects/" + project + "/generations/progress")
                        .header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.stepsRemaining").value(1))
                .andExpect(jsonPath("$.estimatedSeconds").value(45));
    }

    // --- fixtures -------------------------------------------------------------

    private void startGeneration() throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Stripe\",\"agentResearchOnly\":true}"))
                .andExpect(status().isAccepted());
    }

    private void answerFirst(String body) throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/clarifications/" + firstClarificationId() + "/answer")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String firstClarificationId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM generation_clarification WHERE project_id = ?::uuid ORDER BY created_at LIMIT 1",
                String.class, project);
    }

    private long clarificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM generation_clarification WHERE project_id = ?::uuid",
                Long.class, project);
    }

    private List<String> queuedKinds() {
        return jdbc.queryForList("""
                SELECT kind FROM generation_job
                 WHERE project_id = ?::uuid AND status = 'QUEUED' ORDER BY kind
                """, String.class, project);
    }

    private String projectStatus() {
        return jdbc.queryForObject("SELECT status FROM sandbox_project WHERE id = ?::uuid",
                String.class, project);
    }

    private String projectKey() {
        return jdbc.queryForObject("SELECT project_key FROM sandbox_project WHERE id = ?::uuid",
                String.class, project);
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

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by ClarificationTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
