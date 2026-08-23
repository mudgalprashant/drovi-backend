package com.pm.drovi_backend.ai;

/**
 * A model vendor, behind one method.
 *
 * <p>Implementations are resolved <em>by Spring bean name</em> from
 * {@code ai_provider_config.adapter_bean} — never by injecting a concrete type. That is
 * what makes changing provider an UPDATE rather than a release (ADR-0004), and it is why an
 * implementation must be named: {@code @Component("geminiProvider")}.
 *
 * <p>An implementation is responsible for the wire format and nothing else. It does not
 * choose the model, does not consult a cap, does not write a ledger row and does not retry —
 * all four belong to {@link AiGateway}, the only class that may call this one. A provider
 * that ledgered its own calls would be a second writer to invariant 3.
 */
public interface AiProvider {

    /**
     * @param apiKey passed in rather than read here, so a key never has to live on the
     *               cached {@link ProviderConfig} and an adapter has no reason to know
     *               which environment variable it came from
     * @param model  chosen by {@link ModelRouter}, not by the adapter
     * @throws AiProviderException for every failure, so the gateway has exactly one thing to
     *         catch and exactly one place decides what the caller is told
     */
    AiResponse complete(ProviderConfig config, String apiKey, String model, AiRequest request)
            throws AiProviderException;
}
