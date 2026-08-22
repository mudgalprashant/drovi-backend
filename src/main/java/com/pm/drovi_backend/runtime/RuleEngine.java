package com.pm.drovi_backend.runtime;

import com.pm.drovi_backend.domain.ResponseRule;
import com.pm.drovi_backend.repo.ResponseRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies the override layer: the first live rule whose matcher accepts the request wins.
 *
 * <p>A matcher is a conjunction of optional sections — {@code pathParams}, {@code query},
 * {@code headers}, {@code body} — each a flat map of expected values. An empty matcher
 * accepts everything, which is how "always return 503" is written. Comparison is on the
 * string form so that {@code 200} in JSON matches {@code "200"} from a query string; a
 * sandbox rule is a convenience, not a type system.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleEngine {

    private final ResponseRuleRepository rules;

    @Transactional
    public Optional<MockResponse> evaluate(UUID endpointId, MockRequest request, Map<String, String> pathParams) {
        Instant now = Instant.now();
        List<ResponseRule> candidates = rules.findByEndpointIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(endpointId);

        for (ResponseRule rule : candidates) {
            if (!rule.isLive(now) || !matches(rule, request, pathParams)) {
                continue;
            }
            // An N-shot rule that loses the race to another thread must not fire: the
            // conditional UPDATE is the arbiter, so "fail exactly once" stays exactly once.
            if (rule.getRemainingUses() != null && rules.consumeUse(rule.getId()) == 0) {
                continue;
            }
            log.info("runtime.rule.matched endpointId={} ruleId={} status={}",
                    endpointId, rule.getId(), rule.getStatusCode());
            return Optional.of(new MockResponse(
                    rule.getStatusCode(),
                    stringify(rule.getHeaders()),
                    rule.getBody(),
                    endpointId,
                    rule.getId(),
                    rule.getDelayMs(),
                    null));
        }
        return Optional.empty();
    }

    private boolean matches(ResponseRule rule, MockRequest request, Map<String, String> pathParams) {
        Map<String, Object> matcher = rule.getMatcher();
        if (matcher == null || matcher.isEmpty()) {
            return true;
        }
        return sectionMatches(matcher.get("pathParams"), pathParams::get)
                && sectionMatches(matcher.get("query"), name -> request.firstQuery(name).orElse(null))
                && sectionMatches(matcher.get("headers"), name -> request.headers().get(name.toLowerCase()))
                && sectionMatches(matcher.get("body"), name -> {
                    Map<String, Object> body = request.body();
                    Object value = body == null ? null : body.get(name);
                    return value == null ? null : String.valueOf(value);
                });
    }

    private boolean sectionMatches(Object section, java.util.function.Function<String, String> actual) {
        if (section == null) {
            return true;
        }
        if (!(section instanceof Map<?, ?> expected)) {
            // A malformed matcher must not match everything by accident — that would turn
            // a typo into a project-wide outage.
            log.warn("runtime.rule.matcher.malformed section={}", section);
            return false;
        }
        for (Map.Entry<?, ?> entry : expected.entrySet()) {
            String actualValue = actual.apply(String.valueOf(entry.getKey()));
            if (actualValue == null || !actualValue.equals(String.valueOf(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> stringify(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    }
}
