package com.pm.drovi_backend.runtime;

import java.util.Map;
import java.util.UUID;

/**
 * What the sandbox will send back.
 *
 * @param matchedEndpointId null when nothing matched — the most useful row in the request
 *                          log, because an unmatched route usually means the agent
 *                          generated a path the real product does not have
 * @param matchedRuleId     null unless an override rule fired
 * @param delayMs           applied by the caller, not here, so the runtime stays pure
 */
public record MockResponse(int status,
                           Map<String, String> headers,
                           Object body,
                           UUID matchedEndpointId,
                           UUID matchedRuleId,
                           int delayMs,
                           String errorCode) {

    static MockResponse of(int status, Object body, UUID endpointId) {
        return new MockResponse(status, Map.of(), body, endpointId, null, 0, null);
    }

    public static MockResponse error(int status, String errorCode, String message, UUID endpointId) {
        return new MockResponse(status, Map.of(),
                Map.of("error", Map.of("code", errorCode, "message", message)),
                endpointId, null, 0, errorCode);
    }

    MockResponse withDelay(int extraMs) {
        return new MockResponse(status, headers, body, matchedEndpointId, matchedRuleId,
                delayMs + extraMs, errorCode);
    }
}
