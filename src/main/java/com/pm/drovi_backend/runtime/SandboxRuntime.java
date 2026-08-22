package com.pm.drovi_backend.runtime;

import tools.jackson.databind.ObjectMapper;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.domain.ApiEndpoint;
import com.pm.drovi_backend.domain.SandboxCollection;
import com.pm.drovi_backend.domain.SandboxProject;
import com.pm.drovi_backend.domain.SandboxRecord;
import com.pm.drovi_backend.repo.SandboxCollectionRepository;
import com.pm.drovi_backend.repo.SandboxProjectRepository;
import com.pm.drovi_backend.repo.SandboxRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Serves one sandbox call, start to finish.
 *
 * <p>The order below is the contract, and each step exists because skipping it produces a
 * specific wrong behaviour:
 *
 * <ol>
 *   <li>resolve the project — an archived or still-generating sandbox must not serve</li>
 *   <li>authenticate — so the caller's own auth path is exercised</li>
 *   <li>route — most literal template wins</li>
 *   <li><b>rules before data</b> — an override is meaningless if the data answers first</li>
 *   <li>data — invariant 1: behaviour is a consequence of what is stored</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SandboxRuntime {

    /**
     * Query parameters that steer paging rather than filter records. Anything else is a
     * candidate filter — see {@link #buildFilter}.
     */
    private static final Set<String> PAGING_PARAMS =
            Set.of("limit", "offset", "page", "page_size", "pagesize", "per_page", "cursor", "starting_after");

    private final SandboxProjectRepository projects;
    private final SandboxCollectionRepository collections;
    private final SandboxRecordRepository records;
    private final RouteMatcher routeMatcher;
    private final RuleEngine ruleEngine;
    private final TemplateRenderer renderer;
    private final QuotaService quotas;
    private final SandboxAuthenticator authenticator;
    private final AppConfigService config;
    private final ObjectMapper mapper;

    @Transactional
    public MockResponse handle(String projectKey, MockRequest request) {
        Optional<SandboxProject> found = projects.findByProjectKey(projectKey);
        if (found.isEmpty() || !found.get().isServing()) {
            // Identical response for "no such project" and "not ready", deliberately: the
            // project key is a secret, and distinguishing the two confirms which keys exist.
            return MockResponse.error(404, "SANDBOX_NOT_FOUND", "No sandbox is served at this URL.", null);
        }
        SandboxProject project = found.get();

        if (authenticator.authenticate(project, request).isEmpty()) {
            return MockResponse.error(401, "UNAUTHENTICATED", "Missing or invalid API key.", null);
        }

        Optional<RouteMatcher.RouteMatch> route =
                routeMatcher.match(project.getId(), request.method(), request.path());
        if (route.isEmpty()) {
            int status = config.getInt("runtime.unmatched.status", 404);
            log.info("runtime.unmatched projectKey={} method={} path={}",
                    projectKey, request.method(), request.path());
            return MockResponse.error(status, "NOT_FOUND",
                    "No endpoint matches %s %s.".formatted(request.method(), request.path()), null);
        }

        ApiEndpoint endpoint = route.get().endpoint();
        Map<String, String> pathParams = route.get().pathParams();

        MockResponse response = ruleEngine.evaluate(endpoint.getId(), request, pathParams)
                .orElseGet(() -> serveFromData(project, endpoint, request, pathParams));

        return project.getLatencyMs() > 0 ? response.withDelay(project.getLatencyMs()) : response;
    }

    private MockResponse serveFromData(SandboxProject project, ApiEndpoint endpoint,
                                       MockRequest request, Map<String, String> pathParams) {
        if (endpoint.getBehavior() == ApiEndpoint.Behavior.STATIC) {
            return MockResponse.of(endpoint.getSuccessStatus(),
                    renderer.render(endpoint.getResponseTemplate(), Map.of(), Map.of()), endpoint.getId());
        }

        SandboxCollection collection = collections.findById(endpoint.getDataCollectionId()).orElse(null);
        if (collection == null) {
            // A CHECK constraint stops a data-backed endpoint being created without a
            // collection, but the collection can still be dropped afterwards. Report it as
            // ours (502) rather than pretending the resource does not exist.
            log.warn("runtime.binding.missing endpointId={}", endpoint.getId());
            return MockResponse.error(502, "SANDBOX_MISCONFIGURED",
                    "This endpoint is not bound to a data collection.", endpoint.getId());
        }

        return switch (endpoint.getBehavior()) {
            case LIST -> list(project, endpoint, collection, request);
            case GET -> get(endpoint, collection, pathParams);
            case CREATE -> create(project, endpoint, collection, request);
            case UPDATE -> update(project, endpoint, collection, pathParams, request);
            case DELETE -> delete(endpoint, collection, pathParams);
            case STATIC -> throw new IllegalStateException("handled above");
        };
    }

    // --- read ----------------------------------------------------------------

    private MockResponse list(SandboxProject project, ApiEndpoint endpoint,
                              SandboxCollection collection, MockRequest request) {
        int max = config.getInt("runtime.max.page.size", 200);
        int limit = Math.clamp(
                request.firstQuery("limit").or(() -> request.firstQuery("page_size"))
                        .or(() -> request.firstQuery("per_page"))
                        .map(SandboxRuntime::parseIntOrZero)
                        .filter(v -> v > 0)
                        .orElseGet(() -> config.getInt("runtime.default.page.size", 25)),
                1, max);

        int offset = request.firstQuery("offset").map(SandboxRuntime::parseIntOrZero)
                .filter(v -> v >= 0)
                .orElseGet(() -> request.firstQuery("page").map(SandboxRuntime::parseIntOrZero)
                        .filter(p -> p > 1).map(p -> (p - 1) * limit).orElse(0));

        String filter = buildFilter(collection, request);
        List<Map<String, Object>> items = records
                .findFiltered(project.getId(), collection.getId(), filter, limit, offset)
                .stream().map(SandboxRecord::getData).toList();
        long total = records.countFiltered(project.getId(), collection.getId(), filter);

        Map<String, Object> values = new HashMap<>();
        values.put("items", items);
        values.put("count", items.size());
        values.put("total", total);
        values.put("limit", limit);
        values.put("offset", offset);
        values.put("hasMore", offset + items.size() < total);
        values.put("nextCursor", offset + items.size() < total ? String.valueOf(offset + limit) : null);

        return MockResponse.of(endpoint.getSuccessStatus(),
                renderer.render(endpoint.getResponseTemplate(), values, items), endpoint.getId());
    }

    private MockResponse get(ApiEndpoint endpoint, SandboxCollection collection,
                             Map<String, String> pathParams) {
        String key = recordKeyFrom(endpoint, pathParams);
        if (key == null) {
            return MockResponse.error(502, "SANDBOX_MISCONFIGURED",
                    "This endpoint has no key parameter to look up.", endpoint.getId());
        }
        return records.findByCollectionIdAndRecordKey(collection.getId(), key)
                .map(record -> MockResponse.of(endpoint.getSuccessStatus(),
                        renderer.render(endpoint.getResponseTemplate(),
                                Map.of("record", record.getData(), "recordKey", record.getRecordKey()),
                                record.getData()),
                        endpoint.getId()))
                .orElseGet(() -> MockResponse.error(404, "NOT_FOUND",
                        "No %s with id %s.".formatted(collection.getCode(), key), endpoint.getId()));
    }

    // --- write ---------------------------------------------------------------

    private MockResponse create(SandboxProject project, ApiEndpoint endpoint,
                                SandboxCollection collection, MockRequest request) {
        Map<String, Object> body = request.body() == null ? Map.of() : request.body();
        Map<String, Object> data = new LinkedHashMap<>(body);

        String key = Optional.ofNullable(data.get(collection.getKeyField()))
                .map(String::valueOf)
                .filter(v -> !v.isBlank())
                .orElseGet(() -> generateKey(collection));
        data.put(collection.getKeyField(), key);

        quotas.requireCapacityFor(project.getId(), 1, estimateBytes(data));

        if (records.findByCollectionIdAndRecordKey(collection.getId(), key).isPresent()) {
            return MockResponse.error(409, "ALREADY_EXISTS",
                    "A %s with id %s already exists.".formatted(collection.getCode(), key), endpoint.getId());
        }
        records.save(SandboxRecord.create(project.getId(), collection.getId(), key, data));

        return MockResponse.of(endpoint.getSuccessStatus() == 200 ? 201 : endpoint.getSuccessStatus(),
                renderer.render(endpoint.getResponseTemplate(),
                        Map.of("record", data, "recordKey", key), data),
                endpoint.getId());
    }

    private MockResponse update(SandboxProject project, ApiEndpoint endpoint, SandboxCollection collection,
                                Map<String, String> pathParams, MockRequest request) {
        String key = recordKeyFrom(endpoint, pathParams);
        Optional<SandboxRecord> existing = key == null
                ? Optional.empty()
                : records.findByCollectionIdAndRecordKey(collection.getId(), key);
        if (existing.isEmpty()) {
            return MockResponse.error(404, "NOT_FOUND",
                    "No %s with id %s.".formatted(collection.getCode(), key), endpoint.getId());
        }

        // A shallow merge, matching how PATCH behaves in most products: fields present in
        // the body win, everything else survives. The key field is restored afterwards so
        // a body cannot silently re-identify an existing record.
        Map<String, Object> merged = new LinkedHashMap<>(existing.get().getData());
        if (request.body() != null) {
            merged.putAll(request.body());
        }
        merged.put(collection.getKeyField(), key);

        long delta = Math.max(0, estimateBytes(merged) - estimateBytes(existing.get().getData()));
        if (delta > 0) {
            quotas.requireCapacityFor(project.getId(), 0, delta);
        }
        existing.get().replaceData(merged);

        return MockResponse.of(endpoint.getSuccessStatus(),
                renderer.render(endpoint.getResponseTemplate(),
                        Map.of("record", merged, "recordKey", key), merged),
                endpoint.getId());
    }

    private MockResponse delete(ApiEndpoint endpoint, SandboxCollection collection,
                                Map<String, String> pathParams) {
        String key = recordKeyFrom(endpoint, pathParams);
        Optional<SandboxRecord> existing = key == null
                ? Optional.empty()
                : records.findByCollectionIdAndRecordKey(collection.getId(), key);
        if (existing.isEmpty()) {
            return MockResponse.error(404, "NOT_FOUND",
                    "No %s with id %s.".formatted(collection.getCode(), key), endpoint.getId());
        }
        records.delete(existing.get());
        int status = endpoint.getSuccessStatus();
        return new MockResponse(status == 200 ? 204 : status, Map.of(), null, endpoint.getId(), null, 0, null);
    }

    // --- helpers -------------------------------------------------------------

    private static String recordKeyFrom(ApiEndpoint endpoint, Map<String, String> pathParams) {
        if (endpoint.getKeyParam() != null) {
            return pathParams.get(endpoint.getKeyParam());
        }
        // Unambiguous only when the template has exactly one placeholder. Guessing among
        // several would serve the wrong record, which is worse than refusing.
        return pathParams.size() == 1 ? pathParams.values().iterator().next() : null;
    }

    /**
     * Turns query parameters into a jsonb containment filter.
     *
     * <p>Only parameters the collection's schema actually declares become filters. Real
     * products carry query parameters that are not fields — Stripe's {@code expand}, an
     * {@code include}, a cache-buster — and treating those as filters would match nothing
     * and return an empty list for a request that should have succeeded. When the schema
     * declares no properties we filter on nothing, because guessing wrong here fails
     * closed and silently.
     */
    private String buildFilter(SandboxCollection collection, MockRequest request) {
        Set<String> fields = declaredFields(collection);
        Map<String, Object> filter = new LinkedHashMap<>();
        request.query().forEach((name, values) -> {
            if (values.isEmpty() || PAGING_PARAMS.contains(name.toLowerCase()) || !fields.contains(name)) {
                return;
            }
            filter.put(name, values.getFirst());
        });
        try {
            return mapper.writeValueAsString(filter);
        } catch (Exception e) {
            log.warn("runtime.filter.unserialisable collectionId={}", collection.getId(), e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> declaredFields(SandboxCollection collection) {
        Map<String, Object> schema = collection.getRecordSchema();
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) {
            return Set.of();
        }
        return ((Map<String, Object>) properties).keySet();
    }

    private long estimateBytes(Map<String, Object> data) {
        try {
            return mapper.writeValueAsBytes(data).length;
        } catch (Exception e) {
            // Never let an accounting estimate fail a write outright, but never return 0
            // either — an unmeasurable record must still consume quota.
            log.warn("runtime.size.unmeasurable", e);
            return 1024;
        }
    }

    private static String generateKey(SandboxCollection collection) {
        String prefix = collection.getCode().length() > 3
                ? collection.getCode().substring(0, 3)
                : collection.getCode();
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static int parseIntOrZero(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
