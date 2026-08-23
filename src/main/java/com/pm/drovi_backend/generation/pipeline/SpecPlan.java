package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.domain.ApiEndpoint;
import com.pm.drovi_backend.domain.SandboxProject;
import com.pm.drovi_backend.generation.RetryableJobException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A whole sandbox's structure, parsed and checked <em>before</em> a single row is written.
 *
 * <p>This class exists for one rule: <strong>a malformed spec must fail the job, never
 * half-populate a project.</strong> A project with three of its eight routes is worse than a
 * project with none, because it looks finished — the user integrates against it and meets the
 * missing two as 404s from their own code.
 *
 * <p>So parsing and validating are separated from writing. Everything that can be rejected is
 * rejected here, with nothing persisted; {@link SpecWriter} then writes what survived, inside
 * one transaction.
 *
 * <p>Failures here are {@link RetryableJobException} rather than terminal. A model that
 * produced an unusable plan may well produce a usable one next time, and {@code ai.max.attempts}
 * bounds how long we are willing to find out.
 */
record SpecPlan(String projectName,
                SandboxProject.AuthMode authMode,
                String authHeaderName,
                List<Collection> collections,
                List<Endpoint> endpoints) {

    /** A data collection: the records an endpoint serves, and the shape of one. */
    record Collection(String code, String displayName, String description,
                      String keyField, Map<String, Object> recordSchema) {
    }

    record Endpoint(String method, String path, String group, String summary,
                    ApiEndpoint.Behavior behavior, String collection, String keyParam,
                    Map<String, Object> responseTemplate, Integer successStatus) {
    }

    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");
    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /** Behaviours that address one record, and therefore need a way to know which one. */
    private static final Set<ApiEndpoint.Behavior> SINGLE_RECORD = Set.of(
            ApiEndpoint.Behavior.GET, ApiEndpoint.Behavior.UPDATE, ApiEndpoint.Behavior.DELETE);

    @SuppressWarnings("unchecked")
    static SpecPlan parse(Map<String, Object> raw) {
        List<Collection> collections = new ArrayList<>();
        for (Object item : list(raw.get("collections"))) {
            if (!(item instanceof Map<?, ?>)) {
                throw new RetryableJobException("the generated spec is unusable: a collection is not an object");
            }
            Map<String, Object> map = (Map<String, Object>) item;
            String code = string(map.get("code"));
            require(code != null, "a collection has no code");
            collections.add(new Collection(code,
                    string(map.get("displayName")),
                    string(map.get("description")),
                    // The key field is what identifies one record. Defaulting to "id" rather
                    // than failing: it is right far more often than not, and a wrong guess
                    // shows up immediately as a GET that finds nothing.
                    stringOr(map.get("keyField"), "id"),
                    map.get("recordSchema") instanceof Map<?, ?> schema
                            ? (Map<String, Object>) schema : Map.of()));
        }

        List<Endpoint> endpoints = new ArrayList<>();
        for (Object item : list(raw.get("endpoints"))) {
            if (!(item instanceof Map<?, ?>)) {
                throw new RetryableJobException("the generated spec is unusable: an endpoint is not an object");
            }
            Map<String, Object> map = (Map<String, Object>) item;
            String method = string(map.get("method"));
            String path = string(map.get("path"));
            require(method != null && path != null, "an endpoint has no method or path");
            endpoints.add(new Endpoint(
                    method.toUpperCase(Locale.ROOT),
                    // NOT normalised beyond trimming. The path is the imitated product's,
                    // casing included — rewriting it breaks the only promise Drovi makes.
                    path,
                    stringOr(map.get("group"), "Default"),
                    string(map.get("summary")),
                    behaviour(string(map.get("behavior"))),
                    string(map.get("collection")),
                    string(map.get("keyParam")),
                    map.get("responseTemplate") instanceof Map<?, ?> template
                            ? (Map<String, Object>) template : Map.of(),
                    map.get("successStatus") instanceof Number status ? status.intValue() : null));
        }

        SpecPlan plan = new SpecPlan(
                string(raw.get("projectName")),
                authMode(string(raw.get("authMode"))),
                string(raw.get("authHeaderName")),
                collections, endpoints);
        plan.validate();
        return plan;
    }

    /**
     * Everything that would otherwise surface as a 500 at request time, or as an endpoint that
     * silently serves nothing. Each check is a failure a user would meet as "the sandbox is
     * broken" rather than as "generation failed", which is the difference worth paying for.
     */
    private void validate() {
        require(!collections.isEmpty(), "the spec declares no data collections");
        require(!endpoints.isEmpty(), "the spec declares no endpoints");

        Set<String> codes = new HashSet<>();
        for (Collection collection : collections) {
            require(codes.add(collection.code()),
                    "two data collections share the code '%s'".formatted(collection.code()));
        }

        Set<String> routes = new HashSet<>();
        for (Endpoint endpoint : endpoints) {
            require(METHODS.contains(endpoint.method()),
                    "unsupported method '%s'".formatted(endpoint.method()));
            require(endpoint.path().startsWith("/"),
                    "path '%s' does not start with '/'".formatted(endpoint.path()));
            require(routes.add(endpoint.method() + " " + endpoint.path()),
                    "%s %s is declared twice".formatted(endpoint.method(), endpoint.path()));

            if (endpoint.behavior() == ApiEndpoint.Behavior.STATIC) {
                continue;
            }
            // A data-backed endpoint pointing at a collection nobody declared would be
            // rejected by the database's composite key — but as a constraint violation
            // halfway through the write, after other rows had already landed.
            require(endpoint.collection() != null && codes.contains(endpoint.collection()),
                    "%s %s is backed by '%s', which is not one of the declared collections"
                            .formatted(endpoint.method(), endpoint.path(), endpoint.collection()));

            if (SINGLE_RECORD.contains(endpoint.behavior())) {
                requireAddressableRecord(endpoint);
            }
        }
    }

    /**
     * A GET that cannot work out <em>which</em> record it was asked for answers nothing, and
     * does so at request time rather than here. The runtime resolves the key from
     * {@code keyParam}, or from the single path placeholder when there is exactly one — so
     * anything else has to be caught before it is written.
     */
    private void requireAddressableRecord(Endpoint endpoint) {
        List<String> placeholders = placeholdersIn(endpoint.path());
        if (endpoint.keyParam() != null) {
            require(placeholders.contains(endpoint.keyParam()),
                    "%s %s says its key is '%s', which is not in the path"
                            .formatted(endpoint.method(), endpoint.path(), endpoint.keyParam()));
            return;
        }
        require(placeholders.size() == 1,
                "%s %s addresses one record but has %d path parameters and names none as the key"
                        .formatted(endpoint.method(), endpoint.path(), placeholders.size()));
    }

    /** Fills in the key parameter the runtime would have inferred, so the row states it. */
    static Endpoint withInferredKeyParam(Endpoint endpoint) {
        if (endpoint.keyParam() != null || !SINGLE_RECORD.contains(endpoint.behavior())) {
            return endpoint;
        }
        return new Endpoint(endpoint.method(), endpoint.path(), endpoint.group(), endpoint.summary(),
                endpoint.behavior(), endpoint.collection(), placeholdersIn(endpoint.path()).getFirst(),
                endpoint.responseTemplate(), endpoint.successStatus());
    }

    /** Distinct group names, in the order they first appear, so creation order is stable. */
    List<String> groups() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        endpoints.forEach(endpoint -> seen.putIfAbsent(endpoint.group(), true));
        return List.copyOf(seen.keySet());
    }

    private static List<String> placeholdersIn(String path) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATH_PLACEHOLDER.matcher(path);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static ApiEndpoint.Behavior behaviour(String value) {
        if (value == null) {
            return ApiEndpoint.Behavior.LIST;
        }
        try {
            return ApiEndpoint.Behavior.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RetryableJobException("the generated spec is unusable: unknown behavior '%s'".formatted(value));
        }
    }

    /**
     * A replica that waves everything through never exercises the caller's own auth path
     * (decision #40), so an unreadable auth mode becomes BEARER rather than NONE.
     */
    private static SandboxProject.AuthMode authMode(String value) {
        if (value == null) {
            return SandboxProject.AuthMode.BEARER;
        }
        try {
            return SandboxProject.AuthMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SandboxProject.AuthMode.BEARER;
        }
    }

    private static void require(boolean condition, String whatIsWrong) {
        if (!condition) {
            throw new RetryableJobException("the generated spec is unusable: " + whatIsWrong);
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String stringOr(Object value, String fallback) {
        String s = string(value);
        return s != null ? s : fallback;
    }
}
