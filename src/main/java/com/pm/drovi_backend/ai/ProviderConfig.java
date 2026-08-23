package com.pm.drovi_backend.ai;

/**
 * The active row of {@code ai_provider_config}, as the adapter sees it.
 *
 * <p>THE API KEY IS NOT HERE, and must never be added. The row carries the <em>name</em> of
 * the environment variable holding the key ({@code apiKeyEnvVar}); the value is resolved at
 * call time by {@link ProviderRegistry}. That separation is what keeps a database backup
 * from being a credential leak.
 */
public record ProviderConfig(String code,
                             String displayName,
                             String adapterBean,
                             String baseUrl,
                             String model,
                             String authHeaderName,
                             String apiKeyEnvVar,
                             int maxOutputTokens) {
}
