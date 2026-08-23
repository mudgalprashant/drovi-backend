package com.pm.drovi_backend.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Finds the one active provider, its adapter bean and its key.
 *
 * <p>Three lookups that fail in three different ways, kept together because each is a
 * <em>fail-closed</em> point and the failures are easy to confuse: no active row, a row
 * naming a bean nobody implements, and a row naming an environment variable nobody set.
 * Each gets its own message, because the fix for each is different.
 *
 * <p>The configuration is cached; the key is not. A cached credential survives a rotation,
 * which turns "rotate the key" into "rotate the key and redeploy".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderRegistry {

    private final JdbcTemplate jdbc;
    private final ApplicationContext beans;
    private final Environment environment;

    /**
     * INVARIANT: at most one row can be active — {@code ai_provider_config_single_active_uk}
     * enforces it in the database, because a second active provider is not a fallback, it is
     * double billing. The {@code LIMIT 1} here is belt-and-braces, not the control.
     */
    @Cacheable(value = "aiProvider", key = "'active'", unless = "#result == null")
    @Transactional(readOnly = true)
    public ProviderConfig activeProvider() {
        return jdbc.query("""
                SELECT code, display_name, adapter_bean, base_url, model,
                       auth_header_name, api_key_env_var, max_output_tokens
                  FROM ai_provider_config
                 WHERE active
                 ORDER BY priority
                 LIMIT 1
                """,
                rs -> rs.next()
                        ? new ProviderConfig(rs.getString("code"), rs.getString("display_name"),
                        rs.getString("adapter_bean"), rs.getString("base_url"), rs.getString("model"),
                        rs.getString("auth_header_name"), rs.getString("api_key_env_var"),
                        rs.getInt("max_output_tokens"))
                        : null);
    }

    /**
     * Resolved by <em>name</em>, which is the whole point: the database says which adapter
     * runs. Injecting the type instead would put the choice back in the build.
     */
    AiProvider adapterFor(ProviderConfig config) {
        try {
            return beans.getBean(config.adapterBean(), AiProvider.class);
        } catch (BeansException e) {
            throw new AiUnavailableException(
                    "Provider %s names adapter bean '%s', which does not exist. Either the row is wrong or the adapter was never written."
                            .formatted(config.code(), config.adapterBean()));
        }
    }

    /**
     * Read fresh on every call, from the variable the row names. Absence is a
     * <em>configuration</em> failure and fails closed — it must never degrade into calling a
     * provider unauthenticated, which reads as an outage and costs a support afternoon.
     */
    String apiKeyFor(ProviderConfig config) {
        return Optional.ofNullable(environment.getProperty(config.apiKeyEnvVar()))
                .filter(key -> !key.isBlank())
                .orElseThrow(() -> new AiUnavailableException(
                        "Provider %s needs %s and it is not set."
                                .formatted(config.code(), config.apiKeyEnvVar())));
    }

    /** After an operator switches providers with an UPDATE, so the switch does not need a deploy. */
    @CacheEvict(value = "aiProvider", allEntries = true)
    public void refresh() {
        log.info("ai.provider.refreshed");
    }
}
