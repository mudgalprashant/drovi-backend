package com.pm.drovi_backend.ai;

/**
 * A provider resolved and ready to call: its configuration row, the adapter bean named by
 * that row, and the key read from the environment variable the row names.
 *
 * <p>Deliberately not a bean and deliberately not cached. It holds a live credential, so it
 * exists for the length of one call and is then garbage. Only {@link AiGateway} builds one.
 */
record ResolvedProvider(ProviderConfig config, AiProvider adapter, String apiKey) {
}
