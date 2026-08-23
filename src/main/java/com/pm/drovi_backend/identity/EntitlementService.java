package com.pm.drovi_backend.identity;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads plan limits.
 *
 * <p>Cached: the catalog changes a few times a year and is read on every request that
 * needs a limit. `plan_catalog` has no JPA entity on purpose — nothing mutates it from
 * Java, so a mapped entity would be a write path nobody wants.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final JdbcTemplate jdbc;

    @Cacheable(value = "entitlements", key = "#planCode")
    @Transactional(readOnly = true)
    public Entitlements forPlan(String planCode) {
        return jdbc.query("""
                        SELECT code, display_name, max_projects, max_endpoints_per_project,
                               max_records_per_project, max_stored_bytes_per_project,
                               max_mock_requests_per_month, max_ai_tokens_per_month,
                               max_generations_per_month, log_retention_days,
                               price_minor, currency
                          FROM plan_catalog WHERE code = ? AND active
                        """,
                rs -> {
                    if (!rs.next()) {
                        // An account pointing at a missing or retired plan must fail
                        // loudly. Falling back to a default would silently grant whatever
                        // that default allows.
                        throw new DroviException(ErrorCode.INTERNAL, "Plan is not available.");
                    }
                    return new Entitlements(
                            rs.getString("code"),
                            rs.getString("display_name"),
                            rs.getInt("max_projects"),
                            rs.getInt("max_endpoints_per_project"),
                            rs.getLong("max_records_per_project"),
                            rs.getLong("max_stored_bytes_per_project"),
                            rs.getLong("max_mock_requests_per_month"),
                            rs.getLong("max_ai_tokens_per_month"),
                            rs.getInt("max_generations_per_month"),
                            rs.getInt("log_retention_days"),
                            rs.getInt("price_minor"),
                            rs.getString("currency"));
                },
                planCode);
    }
}
