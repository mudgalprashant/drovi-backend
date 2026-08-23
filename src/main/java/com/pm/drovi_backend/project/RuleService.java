package com.pm.drovi_backend.project;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.domain.ResponseRule;
import com.pm.drovi_backend.repo.ResponseRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The override layer: what a user writes when they want something the data cannot express —
 * a 429, an outage, a call that fails exactly once.
 *
 * <p>Rules are deliberately secondary to data. If a project's behaviour is mostly coming
 * from rules rather than records, it has drifted away from invariant 1 and is on its way to
 * being a pile of canned responses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    /** Matches the CHECK constraint on the column; caught here for a clean 400. */
    private static final int MAX_DELAY_MS = 30_000;

    private final ResponseRuleRepository rules;
    private final ApiSpecService spec;

    @Transactional(readOnly = true)
    public List<ResponseRule> list(UUID accountId, UUID projectId, UUID endpointId) {
        spec.requireEndpoint(accountId, projectId, endpointId);
        // Includes disabled rules — the console must show a rule that is switched off,
        // where the runtime's own query deliberately does not.
        return rules.findByEndpointIdOrderByPriorityAscCreatedAtAsc(endpointId);
    }

    @Transactional
    public ResponseRule create(UUID accountId, UUID projectId, UUID endpointId, String name,
                               Integer priority, Map<String, Object> matcher, Integer statusCode,
                               Map<String, Object> headers, Map<String, Object> body,
                               Integer delayMs, Integer remainingUses, Instant expiresAt) {
        spec.requireEndpoint(accountId, projectId, endpointId);
        validate(statusCode, delayMs, remainingUses);

        ResponseRule rule = rules.save(ResponseRule.create(projectId, endpointId,
                name == null || name.isBlank() ? "Rule" : name,
                priority, matcher, statusCode, headers, body, delayMs, remainingUses, expiresAt));
        log.info("rule.created ruleId={} endpointId={} status={} oneShot={}",
                rule.getId(), endpointId, rule.getStatusCode(), remainingUses != null);
        return rule;
    }

    @Transactional(readOnly = true)
    public ResponseRule require(UUID accountId, UUID projectId, UUID ruleId) {
        // Scoped by project, so a rule id from another tenant simply does not resolve.
        ResponseRule rule = rules.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> DroviException.notFound("No such rule."));
        spec.requireEndpoint(accountId, projectId, rule.getEndpointId());
        return rule;
    }

    @Transactional
    public ResponseRule update(UUID accountId, UUID projectId, UUID ruleId, String name,
                               Integer priority, Boolean enabled, Map<String, Object> matcher,
                               Integer statusCode, Map<String, Object> headers,
                               Map<String, Object> body, Integer delayMs, Integer remainingUses) {
        ResponseRule rule = require(accountId, projectId, ruleId);
        validate(statusCode, delayMs, remainingUses);
        rule.update(name, priority, enabled, matcher, statusCode, headers, body, delayMs, remainingUses);
        return rule;
    }

    @Transactional
    public void delete(UUID accountId, UUID projectId, UUID ruleId) {
        rules.delete(require(accountId, projectId, ruleId));
        log.info("rule.deleted ruleId={} projectId={}", ruleId, projectId);
    }

    private static void validate(Integer statusCode, Integer delayMs, Integer remainingUses) {
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "statusCode must be a valid HTTP status (100–599).");
        }
        if (delayMs != null && (delayMs < 0 || delayMs > MAX_DELAY_MS)) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "delayMs must be between 0 and %d.".formatted(MAX_DELAY_MS));
        }
        if (remainingUses != null && remainingUses < 0) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "remainingUses cannot be negative. Omit it for a rule with no use limit.");
        }
    }
}
