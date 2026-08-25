package com.pm.drovi_backend.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abuse control for {@code /s/**}, the one surface anyone on the internet can reach.
 *
 * <p>Until now it had none. A sandbox base URL is unauthenticated by design when
 * {@code auth_mode = NONE}, and every call writes a {@code mock_request_log} row — so an
 * unbounded caller costs storage as well as CPU, on a 500 MB database.
 *
 * <h2>Two buckets, and why both</h2>
 *
 * <ul>
 *   <li><strong>Per project.</strong> Protects the platform from one sandbox, whether the traffic
 *       is an attack or a customer's load test pointed at us by mistake.
 *   <li><strong>Per caller.</strong> Protects a project from one caller, so a stranger who found
 *       somebody's base URL cannot exhaust that project's budget and take it down for its owner.
 * </ul>
 *
 * <p>The caller is identified by the same truncated {@code /24} used in the request log, not a
 * full address. It groups a small network together — imperfect, and deliberate: it means rotating
 * the last octet does not buy a fresh budget, and it keeps full addresses out of memory when we
 * have already decided not to store them.
 *
 * <h2>What this is not</h2>
 *
 * A fixed window, not a sliding one. A caller can send a full budget at the end of one minute and
 * another at the start of the next, so the real worst case is twice the limit across a boundary.
 * That is fine for abuse control — the point is bounding sustained load, not policing a burst —
 * and a sliding window would cost per-request bookkeeping for a distinction nobody here needs.
 *
 * <p>In-process, and correct only because there is one instance (decision #13's reasoning).
 * A second instance would double the effective limit rather than break anything, but the number
 * would stop meaning what it says.
 *
 * <p>This is <em>not</em> the plan's {@code max_mock_requests_per_month}. That is an entitlement
 * to be enforced and billed in Phase 6; this is a platform guard that applies to everyone.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxRateLimiter {

    private static final boolean ENABLED_BY_DEFAULT = false;
    private static final int PROJECT_PER_MINUTE_DEFAULT = 600;
    private static final int CALLER_PER_MINUTE_DEFAULT = 120;

    /** The window. Entries expire a minute after the first request in them. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Bounded on purpose. An unbounded map keyed by anything a caller controls is a memory leak
     * with a friendly name — enough entries for a busy minute, and the rest evicted.
     */
    private final Cache<String, AtomicInteger> windows = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(100_000)
            .build();

    private final AppConfigService config;

    /** What a refusal needs to say. */
    public record Decision(boolean allowed, int retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);
    }

    public Decision check(String projectId, String callerPrefix) {
        if (!config.getBoolean("runtime.rate.limit.enabled", ENABLED_BY_DEFAULT)) {
            return Decision.ALLOWED;
        }

        int projectLimit = config.getInt("runtime.rate.limit.per.minute", PROJECT_PER_MINUTE_DEFAULT);
        if (exceeded("p:" + projectId, projectLimit)) {
            log.info("runtime.rateLimited scope=project projectId={}", projectId);
            return refuse();
        }

        // Only when we know who is calling. A missing address must not collapse every anonymous
        // caller into one shared bucket, which would rate-limit them as though they were one
        // very busy client.
        if (callerPrefix != null && !callerPrefix.isBlank()) {
            int callerLimit = config.getInt("runtime.rate.limit.per.ip.per.minute", CALLER_PER_MINUTE_DEFAULT);
            if (exceeded("c:" + projectId + ":" + callerPrefix, callerLimit)) {
                log.info("runtime.rateLimited scope=caller projectId={}", projectId);
                return refuse();
            }
        }
        return Decision.ALLOWED;
    }

    private boolean exceeded(String key, int limit) {
        if (limit <= 0) {
            // A zero or negative limit means "no traffic", not "unlimited". A fat-fingered config
            // row should stop a sandbox loudly rather than quietly remove its only guard.
            return true;
        }
        return windows.get(key, unused -> new AtomicInteger()).incrementAndGet() > limit;
    }

    private static Decision refuse() {
        return new Decision(false, (int) WINDOW.toSeconds());
    }
}
