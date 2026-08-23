package com.pm.drovi_backend;

import com.pm.drovi_backend.ai.AiCallContext;
import com.pm.drovi_backend.ai.AiCappedException;
import com.pm.drovi_backend.ai.AiGateway;
import com.pm.drovi_backend.ai.AiProvider;
import com.pm.drovi_backend.ai.AiProviderException;
import com.pm.drovi_backend.ai.AiPurpose;
import com.pm.drovi_backend.ai.AiRequest;
import com.pm.drovi_backend.ai.AiResponse;
import com.pm.drovi_backend.ai.AiUnavailableException;
import com.pm.drovi_backend.ai.ModelRouter;
import com.pm.drovi_backend.ai.ProviderConfig;
import com.pm.drovi_backend.ai.ProviderRegistry;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3.1 — the adapter, the ledger and the caps, which ship as one slice on purpose.
 *
 * <p>Every test here runs with <strong>no API key and no network</strong>, against a stub
 * provider registered the same way a real one is: a row in {@code ai_provider_config} naming
 * a bean. That is the point — the controls that stop spending have to be provable before the
 * thing that spends is switched on, exactly as identity was proved with a stub
 * {@code JwtDecoder}.
 *
 * <p>The stub also counts its calls, so "was capped" can be asserted as <em>nothing was
 * sent</em> rather than merely <em>an exception was thrown</em>. A cap that throws after the
 * request leaves has already cost the money it was meant to save.
 */
@SpringBootTest
@Import(AiSpendControlsTest.StubProviderConfig.class)
class AiSpendControlsTest extends PostgresTestBase {

    private static final String STUB_KEY_VAR = "DROVI_STUB_API_KEY";
    private static final String STUB_MODEL = "stub-model-1";

    /** $1.00 per million input tokens, $10.00 per million output. Round numbers make the arithmetic assertable by hand. */
    private static final long INPUT_MICROS_PER_MTOK = 1_000_000L;
    private static final long OUTPUT_MICROS_PER_MTOK = 10_000_000L;

    @DynamicPropertySource
    static void providerKey(DynamicPropertyRegistry registry) {
        // Resolved through Spring's Environment exactly as the real DROVI_GEMINI_API_KEY is,
        // so the registry's "read the variable the row names" path is the one under test.
        registry.add(STUB_KEY_VAR, () -> "stub-key-not-a-real-credential");
    }

    /** A provider that never leaves the process, and remembers whether it was asked to. */
    static class StubProvider implements AiProvider {

        final AtomicInteger calls = new AtomicInteger();
        Function<AiRequest, AiResponse> behaviour = request -> new AiResponse("{}", 0, 0);

        @Override
        public AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request) {
            calls.incrementAndGet();
            return behaviour.apply(request);
        }
    }

    @TestConfiguration
    static class StubProviderConfig {
        @Bean
        StubProvider stubProvider() {
            return new StubProvider();
        }
    }

    @Autowired
    AiGateway gateway;
    @Autowired
    ModelRouter router;
    @Autowired
    ProviderRegistry providers;
    @Autowired
    AppConfigService appConfig;
    @Autowired
    StubProvider stub;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;

    private UUID account;

    @BeforeEach
    void useTheStubProvider() {
        // The daily caps are a SUM over the whole table, so a leftover row from another test
        // class would decide this one's outcome.
        jdbc.update("DELETE FROM ai_call");

        jdbc.update("""
                INSERT INTO ai_provider_config
                    (code, display_name, adapter_bean, base_url, model, auth_header_name,
                     api_key_env_var, max_output_tokens, active, priority)
                VALUES ('STUB','Stub provider','stubProvider','https://stub.invalid',?,
                        'x-stub-key',?, 4096, false, 1)
                ON CONFLICT (code) DO UPDATE
                    SET adapter_bean = EXCLUDED.adapter_bean,
                        api_key_env_var = EXCLUDED.api_key_env_var,
                        model = EXCLUDED.model
                """, STUB_MODEL, STUB_KEY_VAR);
        jdbc.update("""
                INSERT INTO model_pricing
                    (provider_code, model, effective_from, input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('STUB', ?, DATE '2020-01-01', ?, ?)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL, INPUT_MICROS_PER_MTOK, OUTPUT_MICROS_PER_MTOK);

        activateStub();
        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "5000000");
        setConfig("ai.account.daily.cost.cap.micros", "500000");
        // Every gateway test below issues a SPEC call, so SPEC is what must reach the stub.
        // The other purposes stay on the seeded routing, which is what makes the routing
        // test below meaningful rather than circular.
        setConfig("ai.model.SPEC", STUB_MODEL);

        stub.calls.set(0);
        stub.behaviour = request -> new AiResponse("{}", 0, 0);
        account = newAccount();
    }

    @AfterEach
    void restoreTheSeededConfiguration() {
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code = 'STUB'");
        setConfig("ai.enabled", "true");
        setConfig("ai.daily.cost.cap.micros", "5000000");
        setConfig("ai.account.daily.cost.cap.micros", "500000");
        restoreModelRouting();
        providers.refresh();
    }

    // --- the kill switch ------------------------------------------------------

    /**
     * The control that has to work when nothing else does. An operator flipping this at 3am
     * needs the next call to stop, not the next deploy.
     */
    @Test
    void call_whenTheKillSwitchIsOff_isCappedAndNothingIsSent() {
        setConfig("ai.enabled", "false");

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiCappedException.class)
                .extracting(e -> ((AiCappedException) e).getReason())
                .isEqualTo(AiCappedException.Reason.KILL_SWITCH);

        assertThat(stub.calls).hasValue(0);
        assertThat(ledgerStatuses(account)).containsExactly("CAPPED");
    }

    /**
     * A missing config row must not read as "no limit". This is the difference between a
     * fat-fingered DELETE costing nothing and it costing everything.
     */
    @Test
    void call_whenTheKillSwitchRowIsMissing_failsClosed() {
        jdbc.update("DELETE FROM app_config WHERE key = 'ai.enabled'");
        appConfig.refresh();
        try {
            assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                    .isInstanceOf(AiCappedException.class);
            assertThat(stub.calls).hasValue(0);
        } finally {
            jdbc.update("""
                    INSERT INTO app_config (key, value, value_type, description)
                    VALUES ('ai.enabled','true','BOOLEAN','Master kill switch.')
                    ON CONFLICT (key) DO NOTHING
                    """);
            appConfig.refresh();
        }
    }

    // --- the daily caps -------------------------------------------------------

    @Test
    void call_whenThePlatformDailyCapIsSpent_isCappedAndLedgered() {
        setConfig("ai.daily.cost.cap.micros", "1000");
        spendOnBehalfOf(newAccount(), 1000);

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiCappedException.class)
                .extracting(e -> ((AiCappedException) e).getReason())
                .isEqualTo(AiCappedException.Reason.PLATFORM_DAILY_CAP);

        assertThat(stub.calls).hasValue(0);
        assertThat(ledgerStatuses(account)).containsExactly("CAPPED");
    }

    /**
     * The per-account cap exists so one runaway account cannot exhaust the platform's. If a
     * second account were also stopped, the control would be a platform outage triggered by
     * one user — which is the failure it is there to prevent.
     */
    @Test
    void call_whenOneAccountHasSpentItsCap_leavesOtherAccountsWorking() {
        setConfig("ai.account.daily.cost.cap.micros", "1000");
        spendOnBehalfOf(account, 1000);

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiCappedException.class)
                .extracting(e -> ((AiCappedException) e).getReason())
                .isEqualTo(AiCappedException.Reason.ACCOUNT_DAILY_CAP);

        UUID untouched = newAccount();
        assertThat(gateway.call(AiCallContext.forAccount(untouched), someRequest())).isNotNull();
        assertThat(stub.calls).hasValue(1);
    }

    /**
     * Yesterday's spend must not cap today. The boundary is computed in SQL in UTC; getting
     * it wrong shifts the reset by hours and is invisible until somebody is refused at
     * breakfast.
     */
    @Test
    void call_whenTheCapWasSpentYesterday_isAllowedAgainToday() {
        setConfig("ai.account.daily.cost.cap.micros", "1000");
        UUID yesterdaysCall = spendOnBehalfOf(account, 5000);
        jdbc.update("UPDATE ai_call SET created_at = now() - interval '2 days' WHERE id = ?", yesterdaysCall);

        assertThat(gateway.call(AiCallContext.forAccount(account), someRequest())).isNotNull();
        assertThat(stub.calls).hasValue(1);
    }

    // --- the ledger -----------------------------------------------------------

    @Test
    void call_whenTheProviderAnswers_ledgersTokensAndCostAtTheRateInForce() {
        stub.behaviour = request -> new AiResponse("{\"ok\":true}", 2_000, 500);

        AiResponse response = gateway.call(AiCallContext.forAccount(account), someRequest());

        assertThat(response.text()).isEqualTo("{\"ok\":true}");
        Map<String, Object> row = onlyLedgerRow(account);
        assertThat(row).containsEntry("status", "OK")
                .containsEntry("model", STUB_MODEL)
                .containsEntry("purpose", "SPEC")
                .containsEntry("input_tokens", 2_000)
                .containsEntry("output_tokens", 500);
        // 2000 × $1/MTok + 500 × $10/MTok = 2000 + 5000 micro-USD.
        assertThat(row).containsEntry("cost_micros", 7_000L);
        assertThat((Integer) row.get("latency_ms")).isNotNull();
    }

    /**
     * A newer price must never restate what an older call cost. Pricing is effective-dated
     * for exactly this, and the query has to pick the row in force at call time rather than
     * the newest one.
     */
    @Test
    void call_whenAFuturePriceRiseIsScheduled_stillChargesTodaysRate() {
        jdbc.update("""
                INSERT INTO model_pricing
                    (provider_code, model, effective_from, input_micros_per_mtok, output_micros_per_mtok)
                VALUES ('STUB', ?, now()::date + 30, ?, ?)
                ON CONFLICT (provider_code, model, effective_from) DO NOTHING
                """, STUB_MODEL, INPUT_MICROS_PER_MTOK * 4, OUTPUT_MICROS_PER_MTOK * 4);
        stub.behaviour = request -> new AiResponse("{}", 1_000, 0);

        gateway.call(AiCallContext.forAccount(account), someRequest());

        assertThat(onlyLedgerRow(account)).containsEntry("cost_micros", 1_000L);
    }

    /**
     * A failed call that consumed input tokens still cost money, and a ledger recording only
     * successes under-reports exactly when spend is running away.
     */
    @Test
    void call_whenTheProviderFails_ledgersTheFailureAndHidesTheUpstreamMessage() {
        stub.behaviour = request -> {
            throw AiProviderException.error("upstream said: quota project 12345 exhausted", null);
        };

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageNotContaining("12345")
                .hasMessageNotContaining("upstream");

        assertThat(onlyLedgerRow(account)).containsEntry("status", "ERROR");
    }

    @Test
    void call_whenTheProviderTimesOut_isLedgeredAsTimeoutNotError() {
        stub.behaviour = request -> {
            throw AiProviderException.timeout("read timed out", null);
        };

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiProviderException.class);

        // TIMEOUT is not a synonym for ERROR here: the request may have been served and
        // billed, and only our half gave up.
        assertThat(onlyLedgerRow(account)).containsEntry("status", "TIMEOUT");
    }

    // --- failing closed on configuration --------------------------------------

    /**
     * The seeded state of a fresh database: rows exist, none is active. Generation must
     * refuse rather than pick one.
     */
    @Test
    void call_whenNoProviderIsActive_failsClosedWithoutALedgerRow() {
        jdbc.update("UPDATE ai_provider_config SET active = false");
        providers.refresh();

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(stub.calls).hasValue(0);
        assertThat(ledgerStatuses(account)).isEmpty();
    }

    /**
     * Missing credential must not degrade into an unauthenticated call. That reads as a
     * provider outage and costs an afternoon to trace back to an unset variable.
     */
    @Test
    void call_whenTheApiKeyIsNotSet_failsClosedWithoutCallingTheProvider() {
        jdbc.update("UPDATE ai_provider_config SET api_key_env_var = 'DROVI_UNSET_KEY_FOR_TEST' WHERE code = 'STUB'");
        providers.refresh();

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiUnavailableException.class);

        assertThat(stub.calls).hasValue(0);
    }

    /** The public message must not name our environment variables. */
    @Test
    void unavailable_tellsTheOperatorEverythingAndTheCallerNothing() {
        jdbc.update("UPDATE ai_provider_config SET api_key_env_var = 'DROVI_UNSET_KEY_FOR_TEST' WHERE code = 'STUB'");
        providers.refresh();

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("DROVI_UNSET_KEY_FOR_TEST")
                .satisfies(e -> assertThat(((AiUnavailableException) e).getOperatorDetail())
                        .contains("DROVI_UNSET_KEY_FOR_TEST"));
    }

    @Test
    void call_whenTheAdapterBeanDoesNotExist_failsClosed() {
        jdbc.update("UPDATE ai_provider_config SET adapter_bean = 'noSuchProvider' WHERE code = 'STUB'");
        providers.refresh();

        assertThatThrownBy(() -> gateway.call(AiCallContext.forAccount(account), someRequest()))
                .isInstanceOf(AiUnavailableException.class);
    }

    // --- transaction discipline -----------------------------------------------

    /**
     * Generation takes minutes. A connection held for that long exhausts a free-tier pool and
     * takes out every other request in the process — a failure that presents as "the whole
     * app is slow", nowhere near the code that caused it.
     */
    @Test
    void call_insideATransaction_isRefusedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> gateway.call(AiCallContext.forAccount(account), someRequest())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run inside a transaction");

        assertThat(stub.calls).hasValue(0);
    }

    // --- routing --------------------------------------------------------------

    /**
     * SEED is the highest-volume purpose and the one worth routing cheaper. It has to be
     * movable on its own, without dragging the purposes where quality matters.
     */
    @Test
    void modelFor_whenOnePurposeIsRouted_leavesTheOthersWhereTheyWere() {
        setConfig("ai.model.SEED", STUB_MODEL);
        ProviderConfig stubConfig = providers.activeProvider();

        assertThat(router.modelFor(AiPurpose.SEED, stubConfig)).isEqualTo(STUB_MODEL);
        assertThat(router.modelFor(AiPurpose.RESEARCH, stubConfig))
                .isEqualTo(appConfig.get("ai.model.RESEARCH"))
                .isNotEqualTo(STUB_MODEL);
    }

    /** A brand-new provider must work before anybody has written its routing keys. */
    @Test
    void modelFor_withNoRoutingKeysAtAll_usesTheProvidersOwnModel() {
        jdbc.update("DELETE FROM app_config WHERE key LIKE 'ai.model.%'");
        appConfig.refresh();
        try {
            assertThat(router.modelFor(AiPurpose.RESEARCH, providers.activeProvider())).isEqualTo(STUB_MODEL);
        } finally {
            restoreModelRouting();
        }
    }

    // --- fixtures -------------------------------------------------------------

    private static AiRequest someRequest() {
        return AiRequest.of(AiPurpose.SPEC, "You are a spec writer.", "Mimic a card API.");
    }

    private void activateStub() {
        // One active provider at a time is a database invariant, so the others go first.
        jdbc.update("UPDATE ai_provider_config SET active = false WHERE code <> 'STUB'");
        jdbc.update("UPDATE ai_provider_config SET active = true, api_key_env_var = ? WHERE code = 'STUB'",
                STUB_KEY_VAR);
        providers.refresh();
    }

    private void setConfig(String key, String value) {
        jdbc.update("""
                INSERT INTO app_config (key, value, value_type, description)
                VALUES (?, ?, 'STRING', 'set by AiSpendControlsTest')
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
        // The config cache is why a kill switch needs an explicit refresh; forgetting it
        // here would make a test pass against a stale value.
        appConfig.refresh();
    }

    private void restoreModelRouting() {
        for (AiPurpose purpose : AiPurpose.values()) {
            setConfig("ai.model." + purpose.name(), "gemini-3.7-flash");
        }
        setConfig("ai.model.default", "gemini-3.7-flash");
    }

    private UUID newAccount() {
        return jdbc.queryForObject("INSERT INTO accounts (firebase_uid) VALUES (?) RETURNING id",
                UUID.class, "uid-" + UUID.randomUUID());
    }

    /** A ledger row standing in for money already spent today. */
    private UUID spendOnBehalfOf(UUID accountId, long costMicros) {
        return jdbc.queryForObject("""
                INSERT INTO ai_call (account_id, provider_code, model, purpose, cost_micros, status)
                VALUES (?, 'STUB', ?, 'SEED', ?, 'OK') RETURNING id
                """, UUID.class, accountId, STUB_MODEL, costMicros);
    }

    private java.util.List<String> ledgerStatuses(UUID accountId) {
        return jdbc.queryForList("SELECT status FROM ai_call WHERE account_id = ? ORDER BY created_at",
                String.class, accountId);
    }

    private Map<String, Object> onlyLedgerRow(UUID accountId) {
        var rows = jdbc.queryForList("SELECT * FROM ai_call WHERE account_id = ?", accountId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }
}
