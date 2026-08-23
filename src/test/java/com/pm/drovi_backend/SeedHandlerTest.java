package com.pm.drovi_backend;

import com.pm.drovi_backend.ai.AiProvider;
import com.pm.drovi_backend.ai.AiRequest;
import com.pm.drovi_backend.ai.AiResponse;
import com.pm.drovi_backend.ai.ProviderConfig;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.generation.JobHandler;
import com.pm.drovi_backend.generation.JobKind;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3.3, third handler — SEED, and the end of the pipeline.
 *
 * <p>The test that carries the phase is the first one: a project that had routes but nothing
 * behind them starts answering with data, over HTTP, at its own base URL. That is what
 * generation is for.
 *
 * <p>Everything else here is about the two ways seeding goes wrong — writing records the
 * collection cannot address, and writing more than the project is allowed to hold.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SeedHandlerTest.StubsConfig.class)
class SeedHandlerTest extends PostgresTestBase {

    private static final String STUB_KEY_VAR = "DROVI_SEED_STUB_KEY";
    private static final String STUB_MODEL = "seed-stub-model";

    private static final String THREE_CARDS = """
            {"records":[
              {"id":"card_1","status":"ACTIVE","last4":"4242"},
              {"id":"card_2","status":"BLOCKED","last4":"0341"},
              {"id":"card_3","status":"ACTIVE","last4":"1881"}]}""";

    @DynamicPropertySource
    static void providerKey(DynamicPropertyRegistry registry) {
        registry.add(STUB_KEY_VAR, () -> "stub-key-not-a-real-credential");
    }

    static class StubProvider implements AiProvider {
        final AtomicReference<AiRequest> lastRequest = new AtomicReference<>();
        Function<AiRequest, AiResponse> behaviour = request -> new AiResponse(THREE_CARDS, 100, 300);

        @Override
        public AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request) {
            lastRequest.set(request);
            return behaviour.apply(request);
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        StubProvider seedStubProvider() {
            return new StubProvider();
        }
    }

    @Autowired
    JobStore jobs;
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
    private UUID account;
    private UUID project;
    private UUID collection;

    @BeforeEach
    void setUp() {
        runner = new JobRunner(jobs, config, mapper, handlers);
        jdbc.update("DELETE FROM generation_job");

        jdbc.update("""
                INSERT INTO ai_provider_config
                    (code, display_name, adapter_bean, base_url, model, auth_header_name,
                     api_key_env_var, max_output_tokens, active, priority)
                VALUES ('DSTUB','Seed stub','seedStubProvider','https://stub.invalid',?,
                        'x-stub-key',?, 8192, false, 4)
                ON CONFLICT (code) DO UPDATE
                    SET adapter_bean = EXCLUDED.adapter_bean, api_key_env_var = EXCLUDED.api_key_env_var
                """, STUB_MODEL, STUB_KEY_VAR);
        jdbc.update("""
                INSERT INTO model_pricing (provider_code, model, effective_from,
                                           input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('DSTUB', ?, DATE '2020-01-01', 1000000, 1000000)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL);
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code <> 'DSTUB'");
        jdbc.update("UPDATE ai_provider_config SET active = true WHERE code = 'DSTUB'");

        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "50000000");
        setConfig("ai.account.daily.cost.cap.micros", "5000000");
        setConfig("ai.job.runner.enabled", "true");
        setConfig("ai.model.SEED", STUB_MODEL);
        setConfig("ai.seed.records.default", "12");
        setConfig("ai.seed.records.max", "50");

        provider.behaviour = request -> new AiResponse(THREE_CARDS, 100, 300);
        provider.lastRequest.set(null);

        account = newAccount();
        project = newProject(account);
        collection = newCollection(project);
        newListEndpoint(project, collection);
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'DSTUB'");
        setConfig("ai.model.SEED", "gemini-3.7-flash");
        setConfig("ai.seed.records.default", "12");
        setConfig("ai.seed.records.max", "50");
    }

    // --- the end of the pipeline ---------------------------------------------

    /**
     * The phase's whole point, over HTTP: a route that answered with an empty list now answers
     * with records, from the sandbox's own base URL, with no SQL anywhere in the test.
     */
    @Test
    void seed_makesTheGeneratedRoutesReturnData() throws Exception {
        UUID job = enqueueSeed(Map.of("collectionId", collection.toString()));

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("SUCCEEDED");
        mvc.perform(get("/s/" + projectKey() + "/v1/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].id").value(
                        org.hamcrest.Matchers.containsInAnyOrder("card_1", "card_2", "card_3")));
    }

    /** Records are addressable by the collection's own key field, or a GET finds nothing. */
    @Test
    void seed_writesRecordsUnderTheCollectionsKeyField() {
        enqueueSeed(Map.of("collectionId", collection.toString()));

        runner.claimAndRunOne();

        assertThat(jdbc.queryForList(
                "SELECT record_key FROM sandbox_record WHERE collection_id = ? ORDER BY record_key",
                String.class, collection)).containsExactly("card_1", "card_2", "card_3");
    }

    /** The counters quota is enforced against are the trigger's, and they have to move. */
    @Test
    void seed_isAccountedAgainstTheProjectsStorage() {
        enqueueSeed(Map.of("collectionId", collection.toString()));

        runner.claimAndRunOne();

        Map<String, Object> usage = jdbc.queryForMap(
                "SELECT record_count, stored_bytes FROM sandbox_collection WHERE id = ?", collection);
        assertThat((Long) usage.get("record_count")).isEqualTo(3L);
        assertThat((Long) usage.get("stored_bytes")).isPositive();
    }

    // --- how much ------------------------------------------------------------

    @Test
    void seed_asksForTheConfiguredNumberOfRecordsWhenTheCallerNamesNone() {
        setConfig("ai.seed.records.default", "7");
        enqueueSeed(Map.of("collectionId", collection.toString()));

        runner.claimAndRunOne();

        assertThat(provider.lastRequest.get().userContent()).contains("exactly 7 records");
    }

    /** Model-generated rows are billed per token, so "seed 5000 customers" cannot be free. */
    @Test
    void seed_clampsAnEnormousRequestToTheConfiguredCeiling() {
        setConfig("ai.seed.records.max", "20");
        enqueueSeed(Map.of("collectionId", collection.toString(), "count", 5000));

        runner.claimAndRunOne();

        assertThat(provider.lastRequest.get().userContent()).contains("exactly 20 records");
    }

    /** SPEC worked the shape out already; asking for "an array of objects" wastes that. */
    @Test
    void seed_asksForTheShapeSpecAlreadyWorkedOut() {
        enqueueSeed(Map.of("collectionId", collection.toString()));

        runner.claimAndRunOne();

        assertThat(provider.lastRequest.get().responseSchema().toString())
                .contains("last4")
                .contains("status");
    }

    // --- output the collection cannot hold -----------------------------------

    /**
     * Two records with one id would both be written — the duplicate check looks in the
     * database, and neither row is there yet. The collection would then hold two rows under
     * one id, and a GET would return whichever the query reached first.
     */
    @Test
    void seed_whenTwoRecordsShareAnId_retriesRatherThanWritingBoth() {
        UUID job = enqueueSeed(Map.of("collectionId", collection.toString()));
        provider.behaviour = request -> new AiResponse("""
                {"records":[{"id":"card_1","status":"ACTIVE"},{"id":"card_1","status":"BLOCKED"}]}""",
                10, 10);

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(recordCount()).isZero();
    }

    @Test
    void seed_whenTheModelReturnsUnparseableOutput_retries() {
        UUID job = enqueueSeed(Map.of("collectionId", collection.toString()));
        provider.behaviour = request -> new AiResponse("sorry, I cannot do that", 10, 10);

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
        assertThat(recordCount()).isZero();
    }

    @Test
    void seed_whenTheModelReturnsNoRecords_retries() {
        UUID job = enqueueSeed(Map.of("collectionId", collection.toString()));
        provider.behaviour = request -> new AiResponse("{\"records\":[]}", 10, 10);

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("QUEUED");
    }

    // --- quota ----------------------------------------------------------------

    /**
     * Terminal, not retryable: the next attempt writes the same rows into the same full
     * project. Being told to delete something or upgrade beats two more attempts.
     */
    @Test
    void seed_whenTheProjectIsFull_failsTerminallyAndWritesNothing() {
        jdbc.update("UPDATE plan_catalog SET max_records_per_project = 1 WHERE code = 'FREE'");
        UUID job = enqueueSeed(Map.of("collectionId", collection.toString()));
        try {
            runner.claimAndRunOne();

            assertThat(statusOf(job)).isEqualTo("FAILED");
            assertThat(errorCodeOf(job)).isEqualTo("QUOTA_EXCEEDED");
            assertThat(recordCount()).as("quota is checked once for the batch, before any insert").isZero();
        } finally {
            jdbc.update("UPDATE plan_catalog SET max_records_per_project = 500 WHERE code = 'FREE'");
        }
    }

    // --- preconditions --------------------------------------------------------

    @Test
    void seed_withNoCollectionNamed_failsTerminallyWithoutCallingTheModel() {
        UUID job = enqueueSeed(Map.of());

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("FAILED");
        assertThat(errorCodeOf(job)).isEqualTo("SEED_NO_COLLECTION");
        assertThat(provider.lastRequest.get()).isNull();
    }

    /**
     * The collection is resolved with the caller's account and project, so another tenant's
     * id is indistinguishable from one that does not exist.
     */
    @Test
    void seed_withAnotherTenantsCollection_failsWithoutTouchingIt() {
        UUID stranger = newAccount();
        UUID theirProject = newProject(stranger);
        UUID theirCollection = newCollection(theirProject);
        UUID job = enqueueSeed(Map.of("collectionId", theirCollection.toString()));

        runner.claimAndRunOne();

        assertThat(statusOf(job)).isEqualTo("FAILED");
        // NOT_FOUND rather than a retry: a collection that is not yours will not become yours
        // on a second attempt, and the job should say why rather than exhaust its attempts.
        assertThat(errorCodeOf(job)).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sandbox_record WHERE collection_id = ?", Long.class, theirCollection))
                .isZero();
    }

    // --- fixtures -------------------------------------------------------------

    private UUID enqueueSeed(Map<String, Object> input) {
        return jobs.enqueue(account, project, null, JobKind.SEED, "fill it", input).id();
    }

    private UUID newAccount() {
        return jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
    }

    private UUID newProject(UUID accountId) {
        return jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, project_key, name, source_product, status, auth_mode)
                VALUES (?, ?, 'Generated', 'Stripe', 'READY', 'NONE') RETURNING id
                """, UUID.class, accountId, "seed-" + UUID.randomUUID());
    }

    private UUID newCollection(UUID projectId) {
        return jdbc.queryForObject("""
                INSERT INTO sandbox_collection (project_id, code, display_name, key_field, record_schema)
                VALUES (?, 'cards', 'Cards', 'id',
                        '{"id":{"type":"string"},"status":{"type":"string"},"last4":{"type":"string"}}'::jsonb)
                RETURNING id
                """, UUID.class, projectId);
    }

    private void newListEndpoint(UUID projectId, UUID dataCollectionId) {
        UUID group = jdbc.queryForObject(
                "INSERT INTO api_collection (project_id, name) VALUES (?, 'Cards') RETURNING id",
                UUID.class, projectId);
        jdbc.update("""
                INSERT INTO api_endpoint (project_id, collection_id, method, path_template, summary,
                                          behavior, data_collection_id)
                VALUES (?, ?, 'GET', '/v1/cards', 'List cards', 'LIST', ?)
                """, projectId, group, dataCollectionId);
    }

    private String projectKey() {
        return jdbc.queryForObject("SELECT project_key FROM sandbox_project WHERE id = ?",
                String.class, project);
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by SeedHandlerTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        config.refresh();
    }

    private String statusOf(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private String errorCodeOf(UUID jobId) {
        return jdbc.queryForObject("SELECT error_code FROM generation_job WHERE id = ?", String.class, jobId);
    }

    private long recordCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sandbox_record WHERE collection_id = ?",
                Long.class, collection);
    }
}
