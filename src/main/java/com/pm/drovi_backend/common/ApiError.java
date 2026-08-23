package com.pm.drovi_backend.common;

import java.util.Map;

/**
 * The console's error body. One shape, always, so a client can parse failures without
 * branching on which endpoint produced them.
 *
 * <p>{@code correlationId} is the whole point of this class existing: it is what turns
 * "it broke" into a one-line log query, and it is the only internal detail a 5xx is ever
 * allowed to expose.
 */
public record ApiError(Body error) {

    public record Body(String code, String message, String correlationId) {
    }

    public static ApiError of(ErrorCode code, String message, String correlationId) {
        return new ApiError(new Body(code.code(), message, correlationId));
    }

    /**
     * The <em>sandbox</em> surface's shape, which has no correlation id — a sandbox is
     * pretending to be somebody else's API and must not leak Drovi's diagnostics into a
     * response the caller's own error handling will parse.
     */
    public static Map<String, Object> sandboxShaped(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
