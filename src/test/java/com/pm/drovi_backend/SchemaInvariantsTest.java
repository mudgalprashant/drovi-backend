package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the schema properties that leak data or cost money if they break.
 *
 * <p>These are not "does the table exist" tests. Each one asserts a rule that is invisible
 * in the DDL, easy to undo in a later migration by accident, and expensive rather than
 * merely wrong when it goes. The four invariants named in {@code V1__baseline.sql} each
 * have at least one test here; if you are changing one, this file should fail first.
 */
@SpringBootTest
class SchemaInvariantsTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    // --- Invariant 4: tenant isolation ---------------------------------------

    /**
     * The one unrecoverable bug in a store whose entire purpose is holding other people's
     * pretend production data. A record must not be attachable to a collection owned by a
     * different project, and the database — not a code review — has to be what says so.
     */
    @Test
    void record_attachedToAnotherProjectsCollection_rejected() {
        UUID projectA = newProject("tenant-a");
        UUID projectB = newProject("tenant-b");
        UUID collectionOfA = newCollection(projectA, "customers");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sandbox_record (project_id, collection_id, record_key, data)
                VALUES (?, ?, 'cus_1', '{}'::jsonb)
                """, projectB, collectionOfA))
                .as("the composite FK is the only thing standing between two tenants")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Invariant 2: every byte is attributed -------------------------------

    /**
     * Quota is enforced against these counters, so a counter that drifts is a quota that
     * does not exist. The trigger — not the service — is what keeps them true, because a
     * bulk seed, a cascade delete or a hand-run UPDATE during an incident all bypass Java.
     */
    @Test
    void usageCounters_maintainedByTriggerAcrossInsertUpdateDelete() {
        UUID project = newProject("usage");
        UUID collection = newCollection(project, "cards");

        jdbc.update("""
                INSERT INTO sandbox_record (project_id, collection_id, record_key, data)
                VALUES (?, ?, 'card_1', '{"status":"ACTIVE"}'::jsonb)
                """, project, collection);

        assertThat(recordCount(collection)).isEqualTo(1);
        long afterInsert = storedBytes(collection);
        assertThat(afterInsert).as("bytes are counted on insert").isPositive();

        jdbc.update("""
                UPDATE sandbox_record
                   SET data = '{"status":"BLOCKED","blockedReason":"reported lost by the cardholder"}'::jsonb
                 WHERE collection_id = ? AND record_key = 'card_1'
                """, collection);

        assertThat(recordCount(collection)).as("an update is not a new row").isEqualTo(1);
        assertThat(storedBytes(collection)).as("a bigger payload costs more quota").isGreaterThan(afterInsert);

        jdbc.update("DELETE FROM sandbox_record WHERE collection_id = ?", collection);

        assertThat(recordCount(collection)).isZero();
        assertThat(storedBytes(collection)).as("deleting must return the quota, not strand it").isZero();
    }

    // --- Invariant 3: spend is ledgered --------------------------------------

    /**
     * Every model a purpose can be routed to must have a price. Routing {@code ai.model.SEED}
     * to a model with no {@code model_pricing} row does not fail — it records cost_micros = 0
     * for every call, which is the exact failure mode invariant 3 exists to prevent: spend
     * that runs away while the ledger reports zero.
     */
    @Test
    void everyRoutedModel_hasPricing() {
        List<String> unpriced = jdbc.queryForList("""
                SELECT c.value
                  FROM app_config c
                 WHERE c.key LIKE 'ai.model.%'
                   AND NOT EXISTS (SELECT 1 FROM model_pricing p WHERE p.model = c.value)
                """, String.class);

        assertThat(unpriced)
                .as("a routed model with no rate bills silently at zero")
                .isEmpty();
    }

    /**
     * Two active providers is not a failover arrangement — it is two bills.
     */
    @Test
    void onlyOneAiProviderCanBeActiveAtATime() {
        jdbc.update("UPDATE ai_provider_config SET active = true WHERE code = 'ANTHROPIC'");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_provider_config
                  (code, display_name, adapter_bean, base_url, model, auth_header_name,
                   api_key_env_var, active)
                VALUES ('OTHER','Another provider','otherProvider','https://example.test',
                        'some-model','X-Api-Key','DROVI_OTHER_API_KEY', true)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'ANTHROPIC'");
    }

    /**
     * A database dump must never be a credential leak. The provider row records the NAME
     * of the environment variable holding the key, never the key.
     */
    @Test
    void providerConfigStoresNoSecretValue() {
        List<String> suspicious = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'ai_provider_config'
                   AND (column_name LIKE '%api_key%' OR column_name LIKE '%secret%'
                        OR column_name LIKE '%token%' OR column_name LIKE '%password%')
                   -- api_key_env_var holds the NAME of an env var, not a key.
                   -- max_output_tokens is a token COUNT; the word is a coincidence.
                   AND column_name NOT IN ('api_key_env_var', 'max_output_tokens')
                """, String.class);

        assertThat(suspicious)
                .as("secrets belong in the environment, not in a table that gets backed up")
                .isEmpty();
    }

    /**
     * The sandbox issues keys to its own callers, and users reuse keys they were shown.
     * Only the hash and a display prefix may be stored.
     */
    @Test
    void projectApiKeyStoresNoRawKey() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'project_api_key'
                """, String.class);

        assertThat(columns).contains("key_hash", "key_prefix");
        assertThat(columns)
                .as("a raw key column means a leaked backup is a leaked credential")
                .doesNotContain("key", "raw_key", "secret", "token");
    }

    // --- Routing correctness --------------------------------------------------

    /**
     * When two templates both match a request, the more literal one has to win, or
     * {@code /v1/cards/blocked} is served by the handler for {@code /v1/cards/{cardId}}
     * and every caller sees a 404 for a route that exists. specificity is a generated
     * column precisely so no writer can get this ordering wrong.
     */
    @Test
    void specificity_ranksLiteralPathAboveParameterisedPath() {
        UUID project = newProject("routing");
        UUID apiCollection = newApiCollection(project, "Cards");
        newEndpoint(project, apiCollection, "GET", "/v1/cards/{cardId}");
        newEndpoint(project, apiCollection, "GET", "/v1/cards/blocked");

        List<String> byPrecedence = jdbc.queryForList("""
                SELECT path_template FROM api_endpoint
                 WHERE project_id = ? ORDER BY specificity DESC, path_template
                """, String.class, project);

        assertThat(byPrecedence).containsExactly("/v1/cards/blocked", "/v1/cards/{cardId}");
    }

    /**
     * A data-backed endpoint with nothing to read is a 500 discovered by a customer at
     * request time. Refuse it at write time instead.
     */
    @Test
    void dataBackedEndpoint_withoutCollection_rejected() {
        UUID project = newProject("binding");
        UUID apiCollection = newApiCollection(project, "Cards");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO api_endpoint
                    (project_id, collection_id, method, path_template, summary, behavior)
                VALUES (?, ?, 'GET', '/v1/cards', 'List cards', 'LIST')
                """, project, apiCollection))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The free tier has to be a usable product, not an expiring demo: a developer who
     * cannot finish one integration on it never reaches a paid plan. These floors are a
     * product decision, so a migration quietly tightening them should fail the build.
     */
    @Test
    void freePlan_remainsUsable() {
        var free = jdbc.queryForMap("SELECT * FROM plan_catalog WHERE code = 'FREE'");

        assertThat((Integer) free.get("max_projects")).isGreaterThanOrEqualTo(2);
        assertThat((Integer) free.get("max_records_per_project")).isGreaterThanOrEqualTo(500);
        assertThat((Long) free.get("max_stored_bytes_per_project")).isGreaterThanOrEqualTo(5L * 1024 * 1024);
        assertThat((Integer) free.get("price_minor")).isZero();
    }

    // --- fixtures -------------------------------------------------------------

    private UUID newAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id
                """, UUID.class, "uid-" + UUID.randomUUID());
    }

    private UUID newProject(String label) {
        return jdbc.queryForObject("""
                INSERT INTO sandbox_project (account_id, name, source_product)
                VALUES (?, ?, 'Test product') RETURNING id
                """, UUID.class, newAccount(), label);
    }

    private UUID newCollection(UUID projectId, String code) {
        return jdbc.queryForObject("""
                INSERT INTO sandbox_collection (project_id, code, display_name)
                VALUES (?, ?, ?) RETURNING id
                """, UUID.class, projectId, code, code);
    }

    private UUID newApiCollection(UUID projectId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO api_collection (project_id, name) VALUES (?, ?) RETURNING id
                """, UUID.class, projectId, name);
    }

    private void newEndpoint(UUID projectId, UUID collectionId, String method, String pathTemplate) {
        jdbc.update("""
                INSERT INTO api_endpoint (project_id, collection_id, method, path_template, summary)
                VALUES (?, ?, ?, ?, ?)
                """, projectId, collectionId, method, pathTemplate, method + " " + pathTemplate);
    }

    private long recordCount(UUID collectionId) {
        return jdbc.queryForObject(
                "SELECT record_count FROM sandbox_collection WHERE id = ?", Long.class, collectionId);
    }

    private long storedBytes(UUID collectionId) {
        return jdbc.queryForObject(
                "SELECT stored_bytes FROM sandbox_collection WHERE id = ?", Long.class, collectionId);
    }
}
