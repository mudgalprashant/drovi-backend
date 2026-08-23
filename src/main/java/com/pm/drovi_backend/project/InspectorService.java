package com.pm.drovi_backend.project;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The traffic inspector: what the sandbox actually served.
 *
 * <p>A mock API you cannot see into is harder to debug than the real one, which defeats the
 * point of using it. The single most useful row here is one with a null {@code endpointId} —
 * nothing matched, which almost always means a path the spec got wrong.
 *
 * <p>Paged by <b>keyset</b>, not offset. This is the fastest-growing table in the system,
 * and {@code OFFSET 10000} makes the database walk ten thousand rows to discard them. A
 * descending id cursor stays constant-time however large the log gets.
 */
@Service
@RequiredArgsConstructor
public class InspectorService {

    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ProjectService projects;

    public record Entry(long id, Instant at, String method, String path, String query,
                        int statusCode, int latencyMs, String endpointId, String ruleId,
                        String errorCode, boolean matched) {
    }

    public record Page(List<Entry> items, Long nextCursor) {
    }

    /**
     * @param before the {@code nextCursor} from a previous page, or null for the newest
     * @param unmatchedOnly narrow to calls nothing served — the debugging view that matters
     */
    @Transactional(readOnly = true)
    public Page tail(UUID accountId, UUID projectId, Integer limit, Long before, boolean unmatchedOnly) {
        projects.require(accountId, projectId);
        int size = Math.clamp(limit == null ? 50 : limit, 1, MAX_LIMIT);

        // Fetch one extra to discover whether another page exists, without a second COUNT
        // over a table that only grows.
        List<Entry> rows = jdbc.query("""
                        SELECT id, created_at, method, path, query, status_code, latency_ms,
                               endpoint_id, rule_id, error_code
                          FROM mock_request_log
                         WHERE project_id = ?
                           AND (CAST(? AS bigint) IS NULL OR id < CAST(? AS bigint))
                           AND (CAST(? AS boolean) IS FALSE OR endpoint_id IS NULL)
                         ORDER BY id DESC
                         LIMIT ?
                        """,
                (rs, n) -> new Entry(
                        rs.getLong("id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("method"),
                        rs.getString("path"),
                        rs.getString("query"),
                        rs.getInt("status_code"),
                        rs.getInt("latency_ms"),
                        rs.getString("endpoint_id"),
                        rs.getString("rule_id"),
                        rs.getString("error_code"),
                        rs.getString("endpoint_id") != null),
                projectId, before, before, unmatchedOnly, size + 1);

        if (rows.size() > size) {
            List<Entry> page = rows.subList(0, size);
            return new Page(List.copyOf(page), page.getLast().id());
        }
        return new Page(rows, null);
    }
}
