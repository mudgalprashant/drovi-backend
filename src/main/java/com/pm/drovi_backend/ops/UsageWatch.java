package com.pm.drovi_backend.ops;

import com.pm.drovi_backend.common.Correlation;
import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Watches the three numbers that ruin a week, and says so loudly before they do.
 *
 * <p>The system already refuses to exceed its limits — spend has caps, storage has quota, the
 * sandbox surface has rate limits. What it has never had is a way to find out it is <em>close</em>
 * to one. Every existing control announces itself by refusing somebody; this is the part that
 * speaks up while there is still time to do something other than apologise.
 *
 * <h2>Why logs and not metrics</h2>
 *
 * There is nothing scraping this service — no Prometheus, no agent, and no budget for one on a
 * free tier. Metrics nobody collects are a data structure, not an alert. Render captures stdout,
 * so a distinctive line at WARN is the one signal that actually reaches a person here.
 *
 * <p>Each alert carries an {@code action=} naming the runbook procedure, because the project's
 * own rule is that an alert without a runbook action becomes noise and then gets ignored.
 *
 * <h2>Why it is quiet</h2>
 *
 * An alert that fires every five minutes for a day teaches everyone to skip lines beginning
 * {@code alert.}. Each check logs only while over its threshold and only once per run, the run is
 * infrequent, and the thresholds are set where a human still has time to act rather than where
 * the limit actually bites.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UsageWatch {

    private static final boolean ENABLED_BY_DEFAULT = false;
    private static final int SPEND_PERCENT_DEFAULT = 70;
    private static final int STORAGE_PERCENT_DEFAULT = 75;
    private static final int STORAGE_BUDGET_MB_DEFAULT = 400;
    private static final int UNMATCHED_PERCENT_DEFAULT = 25;
    private static final int UNMATCHED_MIN_CALLS_DEFAULT = 50;

    /** One finding. Returned as well as logged so a test asserts the decision, not the logging. */
    public record Alert(String name, String detail) {
    }

    private final JdbcTemplate jdbc;
    private final AppConfigService config;

    @Scheduled(fixedDelayString = "${drovi.ops.watch-interval-ms:900000}",
            initialDelayString = "${drovi.ops.watch-initial-delay-ms:180000}")
    void scheduled() {
        try {
            Correlation.as(this::check);
        } catch (RuntimeException e) {
            log.error("watch.failed", e);
        }
    }

    public List<Alert> check() {
        if (!config.getBoolean("watch.enabled", ENABLED_BY_DEFAULT)) {
            return List.of();
        }
        List<Alert> alerts = new ArrayList<>();
        spendNearingItsCap().ifPresent(alerts::add);
        storageNearingItsBudget().ifPresent(alerts::add);
        tooManyUnmatchedRoutes().ifPresent(alerts::add);

        for (Alert alert : alerts) {
            log.warn("alert.{} {}", alert.name(), alert.detail());
        }
        return alerts;
    }

    /**
     * Spend is the one that costs real money, and the cap stops it — but a cap reached at 2pm
     * means every generation for the rest of the day fails closed, and the first anyone hears is
     * a user asking why nothing works.
     */
    @Transactional(readOnly = true)
    Optional<Alert> spendNearingItsCap() {
        long cap = config.getInt("ai.daily.cost.cap.micros", 0);
        if (cap <= 0) {
            return Optional.empty();
        }
        long spent = jdbc.queryForObject("""
                SELECT coalesce(sum(cost_micros), 0) FROM ai_call
                 WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """, Long.class);

        int percent = (int) (spent * 100 / cap);
        int threshold = config.getInt("watch.spend.percent", SPEND_PERCENT_DEFAULT);
        return percent < threshold ? Optional.empty() : Optional.of(new Alert("spend",
                "percent=%d spentMicros=%d capMicros=%d action=runbook:model-spend-is-running-away"
                        .formatted(percent, spent, cap)));
    }

    /**
     * The database is shared and 500 MB, and the thing that fills it is other people's pretend
     * production data. Storage is measured from the trigger-maintained counters rather than from
     * {@code pg_total_relation_size}: those counters are what quota is enforced against, so
     * alerting on anything else would report a number no control acts on.
     */
    @Transactional(readOnly = true)
    Optional<Alert> storageNearingItsBudget() {
        long budgetBytes = (long) config.getInt("watch.storage.budget.mb", STORAGE_BUDGET_MB_DEFAULT)
                * 1024 * 1024;
        if (budgetBytes <= 0) {
            return Optional.empty();
        }
        long stored = jdbc.queryForObject(
                "SELECT coalesce(sum(stored_bytes), 0) FROM sandbox_collection", Long.class);

        int percent = (int) (stored * 100 / budgetBytes);
        int threshold = config.getInt("watch.storage.percent", STORAGE_PERCENT_DEFAULT);
        return percent < threshold ? Optional.empty() : Optional.of(new Alert("storage",
                "percent=%d storedBytes=%d budgetBytes=%d action=runbook:the-database-is-filling-up"
                        .formatted(percent, stored, budgetBytes)));
    }

    /**
     * The quality signal, and the only one here that is about the product rather than the
     * platform. A high share of calls matching no endpoint means generation is producing paths
     * the real product does not have — users hitting 404s on their own integration and blaming
     * their code. The roadmap names this as the signal that generation quality is not good enough.
     *
     * <p>Guarded by a minimum call count: three requests of which one missed is 33% and means
     * nothing at all.
     */
    @Transactional(readOnly = true)
    Optional<Alert> tooManyUnmatchedRoutes() {
        var counts = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE endpoint_id IS NULL) AS unmatched
                  FROM mock_request_log
                 WHERE created_at > now() - interval '1 hour'
                """);
        long total = (Long) counts.get("total");
        long unmatched = (Long) counts.get("unmatched");

        if (total < config.getInt("watch.unmatched.min.calls", UNMATCHED_MIN_CALLS_DEFAULT)) {
            return Optional.empty();
        }
        int percent = (int) (unmatched * 100 / total);
        int threshold = config.getInt("watch.unmatched.percent", UNMATCHED_PERCENT_DEFAULT);
        return percent < threshold ? Optional.empty() : Optional.of(new Alert("unmatchedRoutes",
                "percent=%d unmatched=%d total=%d window=1h action=runbook:generated-routes-do-not-match"
                        .formatted(percent, unmatched, total)));
    }

}
