package com.pm.drovi_backend.ai;

import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Which model serves which purpose, decided by configuration rather than by code.
 *
 * <p>Three levels, most specific first: {@code ai.model.<PURPOSE>}, then
 * {@code ai.model.default}, then the active provider's own {@code model} column. The point
 * of the chain is that SEED — the highest-volume purpose — can be moved to a cheaper model
 * with one UPDATE, without touching the five purposes where quality matters more.
 *
 * <p>Whether to make that move is a cost/quality decision for a human. Nothing here should
 * ever route a purpose down on its own.
 *
 * <p>INVARIANT: every model this can return must have a {@code model_pricing} row. Routing
 * to an unpriced model does not fail — it ledgers every call at zero, which is precisely the
 * blindness invariant 3 exists to prevent. {@code SchemaInvariantsTest.everyRoutedModel_hasPricing}
 * is the guard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRouter {

    private static final String DEFAULT_KEY = "ai.model.default";

    private final AppConfigService config;

    public String modelFor(AiPurpose purpose, ProviderConfig provider) {
        String routed = config.get("ai.model." + purpose.name());
        if (isSet(routed)) {
            return routed.trim();
        }
        String fallback = config.get(DEFAULT_KEY);
        if (isSet(fallback)) {
            return fallback.trim();
        }
        // The provider row's own model is the last resort rather than a constant, so a
        // brand-new provider works before anyone has written its routing keys.
        log.debug("ai.model.route.fallback purpose={} using provider default", purpose);
        return provider.model();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
