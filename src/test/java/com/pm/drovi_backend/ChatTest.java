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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chat as the front door.
 *
 * <p>The behaviour worth testing is not that messages are stored — it is that <em>the same
 * sentence does the right thing</em>. Against an empty project it builds; against a built one it
 * changes the data. Making a user pick the right endpoint for their sentence would be asking them
 * to know something about our pipeline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ChatTest.StubsConfig.class)
class ChatTest extends PostgresTestBase {

    private static final String STUB_KEY_VAR = "DROVI_CHAT_STUB_KEY";
    private static final String STUB_MODEL = "chat-stub-model";

    private static final String RESEARCH = """
            {"product":"Cards","summary":"Cards.",
             "resources":[{"name":"cards","idField":"id","fields":[{"name":"id","type":"string"}]}],
             "endpoints":[{"method":"GET","path":"/v1/cards","resource":"cards","behavior":"LIST"}],
             "confidence":"HIGH"}""";
    private static final String SPEC = """
            {"projectName":"Cards","authMode":"NONE",
             "collections":[{"code":"cards","keyField":"id","recordSchema":{"id":{"type":"string"}}}],
             "endpoints":[{"method":"GET","path":"/v1/cards","behavior":"LIST","collection":"cards"}]}""";

    @DynamicPropertySource
    static void providerKey(DynamicPropertyRegistry registry) {
        registry.add(STUB_KEY_VAR, () -> "stub-key-not-a-real-credential");
    }

    static class StubProvider implements AiProvider {
        volatile String researchReply = RESEARCH;

        @Override
        public AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request) {
            return switch (request.purpose()) {
                case RESEARCH -> new AiResponse(researchReply, 50, 100);
                case SPEC -> new AiResponse(SPEC, 50, 100);
                case SEED -> new AiResponse("{\"records\":[{\"id\":\"card_1\",\"status\":\"ACTIVE\"}]}", 50, 100);
                case REVISE -> new AiResponse("""
                        {"summary":"Blocked card_1.",
                         "changes":[{"collection":"cards","operation":"UPDATE",
                                     "recordKeys":["card_1"],"set":{"status":"BLOCKED"}}]}""", 50, 100);
                default -> new AiResponse("{}", 10, 10);
            };
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        StubProvider chatStubProvider() {
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
    private String thread;

    @BeforeEach
    void setUp() throws Exception {
        runner = new JobRunner(jobs, config, mapper, pipeline, handlers);
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_thread");
        jdbc.update("DELETE FROM generation_clarification");
        jdbc.update("DELETE FROM generation_job");

        jdbc.update("""
                INSERT INTO ai_provider_config
                    (code, display_name, adapter_bean, base_url, model, auth_header_name,
                     api_key_env_var, max_output_tokens, active, priority)
                VALUES ('CHSTUB','Chat stub','chatStubProvider','https://stub.invalid',?,
                        'x-stub-key',?, 8192, false, 9)
                ON CONFLICT (code) DO UPDATE
                    SET adapter_bean = EXCLUDED.adapter_bean, api_key_env_var = EXCLUDED.api_key_env_var
                """, STUB_MODEL, STUB_KEY_VAR);
        jdbc.update("""
                INSERT INTO model_pricing (provider_code, model, effective_from,
                                           input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('CHSTUB', ?, DATE '2020-01-01', 1000000, 1000000)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL);
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code <> 'CHSTUB'");
        jdbc.update("UPDATE ai_provider_config SET active = true WHERE code = 'CHSTUB'");

        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "50000000");
        setConfig("ai.account.daily.cost.cap.micros", "5000000");
        setConfig("ai.job.runner.enabled", "true");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), STUB_MODEL);
        }

        provider.researchReply = RESEARCH;
        user = "uid-" + UUID.randomUUID();
        project = createProject();
        thread = createThread();
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'CHSTUB'");
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), "gemini-3.7-flash");
        }
    }

    // --- the same sentence, the right action ---------------------------------

    @Test
    void aMessageAgainstAnEmptyProject_startsAGeneration() throws Exception {
        say("mimic a cards API");

        assertThat(jobKinds()).containsExactly("RESEARCH");
    }

    @Test
    void aMessageAgainstABuiltProject_changesItsDataInstead() throws Exception {
        say("mimic a cards API");
        drain();

        say("make card_1 blocked");
        drain();

        assertThat(jdbc.queryForObject("""
                SELECT data->>'status' FROM sandbox_record
                 WHERE project_id = ?::uuid AND record_key = 'card_1'
                """, String.class, project)).isEqualTo("BLOCKED");
    }

    /** The end goal's "wait a defined time", said in the conversation rather than polled for. */
    @Test
    void theReply_saysHowLongToWait() throws Exception {
        mvc.perform(post("/api/v1/threads/" + thread + "/messages")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"mimic a cards API\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.content").value(
                        org.hamcrest.Matchers.containsString("about")));
    }

    // --- the transcript -------------------------------------------------------

    /** The message the whole thing exists to produce, with the address to point an app at. */
    @Test
    void whenTheSandboxIsReady_theConversationSaysSoAndGivesTheBaseUrl() throws Exception {
        say("mimic a cards API");
        drain();

        assertThat(lastAssistantMessage())
                .contains("ready")
                .contains("/s/" + project);
    }

    /** A question is the one moment the generation genuinely needs the user back. */
    @Test
    void whenSomethingIsAmbiguous_theConversationAsks() throws Exception {
        provider.researchReply = """
                {"product":"Cards","summary":"Cards.",
                 "resources":[{"name":"cards","idField":"id","fields":[{"name":"id","type":"string"}]}],
                 "endpoints":[{"method":"GET","path":"/v1/cards","resource":"cards","behavior":"LIST"}],
                 "confidence":"LOW",
                 "questions":[{"question":"Which field marks a card blocked?",
                               "options":[{"label":"status"},{"label":"blocked"}]}]}""";

        say("mimic a cards API");
        drain();

        assertThat(lastAssistantMessage()).contains("Which field marks a card blocked?");
    }

    /** Ordered by seq, because a question and its answer can land in the same millisecond. */
    @Test
    void theTranscript_isOrderedAndAttributed() throws Exception {
        say("mimic a cards API");
        drain();

        mvc.perform(get("/api/v1/threads/" + thread + "/messages")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].content").value("mimic a cards API"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].seq").value(2));
    }

    /** A generation driven through the REST API has no thread, and must not fail for it. */
    @Test
    void aProjectWithNoConversation_generatesWithoutNarration() throws Exception {
        String bare = createProject();
        mvc.perform(post("/api/v1/projects/" + bare + "/generations")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Cards\",\"agentResearchOnly\":true}"))
                .andExpect(status().isAccepted());
        drain();

        assertThat(jdbc.queryForObject("SELECT status FROM sandbox_project WHERE id = ?::uuid",
                String.class, bare)).isEqualTo("READY");
    }

    // --- tenancy --------------------------------------------------------------

    @Test
    void anotherTenantsConversation_isNotFound() throws Exception {
        mvc.perform(get("/api/v1/threads/" + thread + "/messages")
                        .header("Authorization", "Bearer uid-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void threads_areListedForTheirOwnerOnly() throws Exception {
        mvc.perform(get("/api/v1/threads").header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/threads").header("Authorization", "Bearer uid-" + UUID.randomUUID()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anEmptyMessage_isABadRequest() throws Exception {
        mvc.perform(post("/api/v1/threads/" + thread + "/messages")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // --- fixtures -------------------------------------------------------------

    private void say(String text) throws Exception {
        mvc.perform(post("/api/v1/threads/" + thread + "/messages")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("message", text))))
                .andExpect(status().isAccepted());
    }

    private void drain() {
        for (int tick = 0; tick < 20 && runner.claimAndRunOne(); tick++) {
            // one job per tick
        }
    }

    private String lastAssistantMessage() {
        return jdbc.queryForObject("""
                SELECT content FROM chat_message
                 WHERE thread_id = ?::uuid AND role = 'ASSISTANT' ORDER BY seq DESC LIMIT 1
                """, String.class, thread);
    }

    private List<String> jobKinds() {
        return jdbc.queryForList("SELECT kind FROM generation_job WHERE project_id = ?::uuid ORDER BY kind",
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

    private String createThread() throws Exception {
        String body = mvc.perform(post("/api/v1/projects/" + project + "/threads")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cards\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by ChatTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }
}
