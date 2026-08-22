package com.pm.drovi_backend.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Invariant 2's enforcement point: storage is the metered resource, so a write that would
 * take a project past its plan is refused before it happens.
 *
 * <p>Totals are a SUM over the project's handful of {@code sandbox_collection} rows rather
 * than a counter on {@code sandbox_project}. That is the whole reason the counters live
 * where they do: a project-level counter would put every insert of a ten-thousand-row
 * seed behind one row lock, and bulk seeding is the single most common write this system
 * performs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    private final JdbcTemplate jdbc;

    public record Usage(long records, long bytes, long maxRecords, long maxBytes) {

        public boolean wouldExceed(long extraRecords, long extraBytes) {
            return records + extraRecords > maxRecords || bytes + extraBytes > maxBytes;
        }

        public boolean isFull() {
            return records >= maxRecords || bytes >= maxBytes;
        }
    }

    @Transactional(readOnly = true)
    public Usage usageOf(UUID projectId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(sc.record_count), 0) AS records,
                       coalesce(sum(sc.stored_bytes), 0) AS bytes,
                       pc.max_records_per_project        AS max_records,
                       pc.max_stored_bytes_per_project   AS max_bytes
                  FROM sandbox_project sp
                  JOIN accounts a      ON a.id = sp.account_id
                  JOIN plan_catalog pc ON pc.code = a.plan_code
                  LEFT JOIN sandbox_collection sc ON sc.project_id = sp.id
                 WHERE sp.id = ?
                 GROUP BY pc.max_records_per_project, pc.max_stored_bytes_per_project
                """,
                (rs, row) -> new Usage(rs.getLong("records"), rs.getLong("bytes"),
                        rs.getLong("max_records"), rs.getLong("max_bytes")),
                projectId);
    }

    /**
     * @throws QuotaExceededException so the caller cannot proceed by ignoring a boolean.
     *         A quota check whose result can be dropped is a quota check that will be.
     */
    @Transactional(readOnly = true)
    public void requireCapacityFor(UUID projectId, long extraRecords, long extraBytes) {
        Usage usage = usageOf(projectId);
        if (usage.wouldExceed(extraRecords, extraBytes)) {
            log.info("quota.exceeded projectId={} records={}/{} bytes={}/{}",
                    projectId, usage.records(), usage.maxRecords(), usage.bytes(), usage.maxBytes());
            throw new QuotaExceededException(usage);
        }
    }

    public static class QuotaExceededException extends RuntimeException {

        private final transient Usage usage;

        QuotaExceededException(Usage usage) {
            super("project storage quota exceeded");
            this.usage = usage;
        }

        public Usage usage() {
            return usage;
        }
    }
}
