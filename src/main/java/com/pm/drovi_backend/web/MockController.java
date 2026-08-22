package com.pm.drovi_backend.web;

import tools.jackson.databind.ObjectMapper;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.runtime.MockRequest;
import com.pm.drovi_backend.runtime.MockResponse;
import com.pm.drovi_backend.runtime.QuotaService;
import com.pm.drovi_backend.runtime.SandboxRuntime;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The public face of every sandbox: {@code /s/{projectKey}/**}.
 *
 * <p>A catch-all rather than generated routes, because the whole promise is that the
 * caller's existing client hits paths Drovi never compiled in. This class parses,
 * delegates and maps — every decision belongs to {@link SandboxRuntime}, which is why that
 * class can be tested without a servlet.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class MockController {

    private final SandboxRuntime runtime;
    private final MockRequestLogger requestLogger;
    private final AppConfigService config;
    private final ObjectMapper mapper;

    @RequestMapping("/s/{projectKey}/**")
    ResponseEntity<Object> handle(@PathVariable String projectKey, HttpServletRequest servletRequest)
            throws IOException {

        long startedAt = System.nanoTime();
        MockRequest request = adapt(projectKey, servletRequest);

        MockResponse response;
        try {
            response = runtime.handle(projectKey, request);
        } catch (QuotaService.QuotaExceededException e) {
            // 507 rather than 400: nothing is wrong with the request. The project is full,
            // and the caller needs to know it is a storage limit, not their payload.
            response = MockResponse.error(HttpStatus.INSUFFICIENT_STORAGE.value(), "QUOTA_EXCEEDED",
                    "This sandbox has reached its plan's storage limit (%d/%d records)."
                            .formatted(e.usage().records(), e.usage().maxRecords()), null);
        }

        applyDelay(response.delayMs());
        int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
        requestLogger.record(projectKey, request, response, latencyMs, clientPrefix(servletRequest));

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON);
        response.headers().forEach(builder::header);
        return response.body() == null ? builder.build() : builder.body(response.body());
    }

    private MockRequest adapt(String projectKey, HttpServletRequest servletRequest) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(servletRequest.getHeaderNames())) {
            headers.put(name.toLowerCase(), servletRequest.getHeader(name));
        }

        Map<String, Object> body = null;
        String contentType = servletRequest.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            byte[] raw = servletRequest.getInputStream().readAllBytes();
            int maxBytes = config.getInt("runtime.max.request.bytes", 1_048_576);
            if (raw.length > maxBytes) {
                throw new IOException("request body exceeds runtime.max.request.bytes");
            }
            if (raw.length > 0) {
                try {
                    body = mapper.readValue(raw, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
                } catch (RuntimeException e) {
                    // A malformed body is the caller's business: the runtime sees no body
                    // and the matching rules decide. Failing here would hide which
                    // endpoint they were aiming at.
                    log.debug("runtime.body.unparseable projectKey={}", projectKey);
                }
            }
        }

        return new MockRequest(servletRequest.getMethod().toUpperCase(Locale.ROOT),
                remainingPath(projectKey, servletRequest),
                parseQuery(servletRequest.getQueryString()),
                headers,
                body);
    }

    /**
     * Everything after {@code /s/{projectKey}}, decoded one segment at a time.
     *
     * <p>Per segment, not whole-string, because a record id containing an encoded slash
     * ({@code %2F}) must stay inside its own segment instead of splitting the path.
     */
    private static String remainingPath(String projectKey, HttpServletRequest servletRequest) {
        String uri = servletRequest.getRequestURI();
        String prefix = servletRequest.getContextPath() + "/s/" + projectKey;
        String remainder = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
        if (remainder.isEmpty()) {
            return "/";
        }
        StringJoiner joiner = new StringJoiner("/");
        for (String segment : remainder.split("/", -1)) {
            joiner.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }

    private static Map<String, List<String>> parseQuery(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> query = new LinkedHashMap<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return query;
    }

    /**
     * Truncated to a /24 (or /48 for IPv6): enough to tell two callers apart while
     * debugging, not enough to be a personal identifier we then have to defend.
     */
    private static String clientPrefix(HttpServletRequest servletRequest) {
        String ip = Optional.ofNullable(servletRequest.getHeader("X-Forwarded-For"))
                .map(v -> v.split(",")[0].trim())
                .filter(v -> !v.isEmpty())
                .orElseGet(servletRequest::getRemoteAddr);
        if (ip == null) {
            return null;
        }
        if (ip.contains(":")) {
            String[] groups = ip.split(":");
            return String.join(":", Arrays.copyOf(groups, Math.min(3, groups.length))) + "::/48";
        }
        String[] octets = ip.split("\\.");
        return octets.length == 4 ? "%s.%s.%s.0/24".formatted(octets[0], octets[1], octets[2]) : null;
    }

    private void applyDelay(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            // Virtual threads are enabled, so a simulated latency parks a continuation
            // rather than pinning a platform thread — this is why latency simulation is
            // affordable on a free instance at all.
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
