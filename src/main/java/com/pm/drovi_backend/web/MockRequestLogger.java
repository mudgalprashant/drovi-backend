package com.pm.drovi_backend.web;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.runtime.MockRequest;
import com.pm.drovi_backend.runtime.MockResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes the inspector's audit trail.
 *
 * <p>Runs in its own transaction and swallows its own failures on purpose: the log exists
 * to explain the response, and it must never be the reason a caller does not get one.
 * A sandbox that 500s because its logging table is full has failed at its only job.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class MockRequestLogger {

    private final JdbcTemplate jdbc;
    private final AppConfigService config;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(String projectKey, MockRequest request, MockResponse response,
                int latencyMs, String clientPrefix) {
        if (!config.getBoolean("runtime.log.enabled", true)) {
            return;
        }
        try {
            UUID endpointId = response.matchedEndpointId();
            jdbc.update("""
                    INSERT INTO mock_request_log
                        (project_id, endpoint_id, rule_id, method, path, query, status_code,
                         latency_ms, client_ip_prefix, error_code)
                    SELECT sp.id, ?, ?, ?, ?, ?, ?, ?, ?, ?
                      FROM sandbox_project sp WHERE sp.project_key = ?
                    """,
                    endpointId, response.matchedRuleId(), request.method(), request.path(),
                    request.query().isEmpty() ? null : request.query().toString(),
                    response.status(), latencyMs, clientPrefix, response.errorCode(), projectKey);
        } catch (RuntimeException e) {
            log.warn("runtime.log.failed projectKey={} status={}", projectKey, response.status(), e);
        }
    }
}
