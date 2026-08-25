package com.pm.drovi_backend.ops;

import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes request-log rows past their plan's retention.
 *
 * <p>{@code mock_request_log} is the fastest-growing table in the system — one row per served
 * call, including the ones that matched nothing — and until now nothing removed anything from it.
 * {@code plan_catalog.log_retention_days} has been a column that described an intention. On a
 * 500 MB shared database, this is what fills it, and it fills it while everything looks fine.
 *
 * <h2>Why it is batched</h2>
 *
 * The inspector reads this table, and a single {@code DELETE} of a few million rows holds locks
 * and bloats one transaction for as long as it takes. Small statements, each committed, let the
 * purge run against a live system without anyone noticing — which matters because a purge that
 * has to be scheduled for a quiet hour is a purge that gets postponed.
 *
 * <p>A run is bounded rather than the backlog: a large arrears is cleared over several runs. The
 * alternative is one run that behaves like the unbatched delete it was meant to replace.
 *
 * <h2>Retention is per plan, not global</h2>
 *
 * Retention is something a plan sells, so the cutoff is each project's own. Doing it in one
 * statement — joining through to {@code plan_catalog} — avoids walking projects in Java and
 * issuing a query each, which on a few thousand projects is its own problem.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RequestLogPurge {

    private static final boolean ENABLED_BY_DEFAULT = false;
    private static final int BATCH_SIZE_DEFAULT = 5_000;
    private static final int MAX_BATCHES_DEFAULT = 20;

    private final JdbcTemplate jdbc;
    private final AppConfigService config;

    @Scheduled(fixedDelayString = "${drovi.ops.purge-interval-ms:3600000}",
            initialDelayString = "${drovi.ops.purge-initial-delay-ms:120000}")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            // A scheduled method that throws is unscheduled by some executors and merely logged
            // by others. Neither is a thing to discover when the database is full.
            log.error("purge.failed", e);
        }
    }

    /**
     * Separate from the schedule so a test can assert the work rather than the absence of an
     * exception — and public for the same reason {@code JobRunner.claimAndRunOne} is: an operator
     * watching a database fill wants to run one now, not wait for the hour.
     *
     * @return how many rows went
     */
    public int purge() {
        if (!config.getBoolean("purge.enabled", ENABLED_BY_DEFAULT)) {
            return 0;
        }
        int batchSize = config.getInt("purge.batch.size", BATCH_SIZE_DEFAULT);
        int maxBatches = config.getInt("purge.max.batches", MAX_BATCHES_DEFAULT);

        int deleted = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int rows = deleteBatch(batchSize);
            deleted += rows;
            if (rows < batchSize) {
                // Short batch means the backlog is gone. Stopping here keeps a normal run to one
                // cheap statement rather than twenty.
                break;
            }
        }
        if (deleted > 0) {
            log.info("purge.completed rows={}", deleted);
        }
        return deleted;
    }

    /**
     * One committed statement. The subquery picks ids first so the delete works from a bounded
     * set — {@code DELETE … WHERE created_at < …} with a limit is not expressible, and without a
     * limit it is the unbatched delete this exists to avoid.
     */
    @Transactional
    int deleteBatch(int batchSize) {
        return jdbc.update("""
                DELETE FROM mock_request_log
                 WHERE id IN (
                    SELECT log.id
                      FROM mock_request_log log
                      JOIN sandbox_project sp ON sp.id = log.project_id
                      JOIN accounts a         ON a.id = sp.account_id
                      JOIN plan_catalog pc    ON pc.code = a.plan_code
                     WHERE log.created_at < now() - make_interval(days => pc.log_retention_days)
                     LIMIT ?)
                """, batchSize);
    }
}
