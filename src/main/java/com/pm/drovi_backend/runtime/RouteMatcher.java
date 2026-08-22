package com.pm.drovi_backend.runtime;

import com.pm.drovi_backend.domain.ApiEndpoint;
import com.pm.drovi_backend.repo.ApiEndpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an inbound path to the endpoint that should serve it.
 *
 * <p>The compiled patterns are cached but <em>the endpoint list is not</em>, and that is
 * deliberate. A pattern is a pure function of a template string and can never go stale;
 * the route table changes the moment the chat adds an endpoint, and a user who is told
 * their new route is live should not meet a 404 for the next ten minutes. The lookup is
 * a single indexed read, which is the cheaper thing to spend.
 */
@Component
@RequiredArgsConstructor
public class RouteMatcher {

    /** A path segment that is entirely a placeholder: {@code {cardId}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{([A-Za-z0-9_]+)}$");

    private final ApiEndpointRepository endpoints;
    private final Map<String, CompiledRoute> compiled = new ConcurrentHashMap<>();

    public record RouteMatch(ApiEndpoint endpoint, Map<String, String> pathParams) {
    }

    @Transactional(readOnly = true)
    public Optional<RouteMatch> match(UUID projectId, String method, String path) {
        String normalised = normalise(path);
        // Ordered most-literal-first by the query, so the first hit is the right one.
        for (ApiEndpoint endpoint : endpoints
                .findByProjectIdAndMethodOrderBySpecificityDescPathTemplateAsc(projectId, method)) {
            Optional<Map<String, String>> params = compiledFor(endpoint.getPathTemplate()).match(normalised);
            if (params.isPresent()) {
                return Optional.of(new RouteMatch(endpoint, params.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * A trailing slash is a formatting difference, not a different resource. Real products
     * overwhelmingly treat them the same, and a sandbox that does not is a sandbox that
     * fails for a reason the user will never guess.
     */
    private static String normalise(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private CompiledRoute compiledFor(String template) {
        return compiled.computeIfAbsent(template, RouteMatcher::compile);
    }

    private static CompiledRoute compile(String template) {
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        for (String segment : normalise(template).split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            regex.append('/');
            Matcher placeholder = PLACEHOLDER.matcher(segment);
            if (placeholder.matches()) {
                names.add(placeholder.group(1));
                // Not greedy across '/': a placeholder is exactly one segment, so
                // /v1/cards/{id} must not swallow /v1/cards/a/b.
                regex.append("([^/]+)");
            } else {
                // Quoted, so a product whose path contains '.' or '+' still matches
                // literally instead of becoming an accidental wildcard.
                regex.append(Pattern.quote(segment));
            }
        }
        if (regex.isEmpty()) {
            regex.append('/');
        }
        return new CompiledRoute(Pattern.compile("^" + regex + "$"), List.copyOf(names));
    }

    private record CompiledRoute(Pattern pattern, List<String> paramNames) {

        Optional<Map<String, String>> match(String path) {
            Matcher m = pattern.matcher(path);
            if (!m.matches()) {
                return Optional.empty();
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), m.group(i + 1));
            }
            return Optional.of(params);
        }
    }
}
