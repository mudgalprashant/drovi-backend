package com.pm.drovi_backend.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An inbound sandbox call, already detached from the servlet API.
 *
 * <p>Deliberately a plain record: the runtime is the part of this system most worth
 * testing exhaustively, and it should be drivable from a unit test without a web layer.
 *
 * @param path   the portion after {@code /s/{projectKey}}, always starting with '/'
 * @param headers header names lowercased, because callers disagree about capitalisation
 */
public record MockRequest(String method,
                          String path,
                          Map<String, List<String>> query,
                          Map<String, String> headers,
                          Map<String, Object> body) {

    public Optional<String> firstQuery(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase()));
    }
}
