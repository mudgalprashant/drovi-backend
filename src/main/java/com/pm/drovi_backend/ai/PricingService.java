package com.pm.drovi_backend.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * What a call cost, at the rate in force when it happened.
 *
 * <p>"When it happened" is the whole design. {@code model_pricing} is effective-dated and
 * the query takes the latest row not in the future, so a price change is an INSERT made in
 * advance and a call made in December is still costed at December's rate after January's
 * increase lands. Rates as constants would silently restate what last month cost.
 *
 * <p>Not cached. This runs once per model call — a request that has just spent seconds
 * talking to a provider — so the lookup is free in context, and a cache here would be one
 * more thing to invalidate on the day a price changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private static final long MICROS_PER_MTOK_DIVISOR = 1_000_000L;

    private final JdbcTemplate jdbc;

    /** Input and output rates in micro-USD per million tokens. */
    public record Rate(long inputMicrosPerMtok, long outputMicrosPerMtok) {

        static final Rate FREE = new Rate(0, 0);
    }

    /**
     * Falls back to zero rather than failing the call. A model with no price row is a
     * configuration bug, not a reason to lose work the user already paid tokens for — but it
     * is logged at WARN because it silently under-reports spend, which is exactly the
     * failure invariant 3 exists to prevent.
     *
     * <p>The real defence is upstream: {@code SchemaInvariantsTest.everyRoutedModel_hasPricing}
     * fails the build if a routed purpose points at an unpriced model.
     */
    @Transactional(readOnly = true)
    public Rate rateFor(String providerCode, String model, Instant at) {
        Rate rate = jdbc.query("""
                SELECT input_micros_per_mtok, output_micros_per_mtok
                  FROM model_pricing
                 WHERE provider_code = ?
                   AND model = ?
                   AND effective_from <= ?
                 ORDER BY effective_from DESC
                 LIMIT 1
                """,
                rs -> rs.next() ? new Rate(rs.getLong(1), rs.getLong(2)) : null,
                providerCode, model, java.sql.Date.valueOf(at.atZone(ZoneOffset.UTC).toLocalDate()));

        if (rate == null) {
            log.warn("ai.pricing.missing provider={} model={} — this call is ledgered at zero cost", providerCode, model);
            return Rate.FREE;
        }
        return rate;
    }

    /**
     * Integer arithmetic throughout, in micro-units. Floating-point money accumulates
     * rounding across thousands of small calls, and the cap it feeds is a hard limit that
     * has to be exactly comparable.
     */
    public long costMicros(Rate rate, int inputTokens, int outputTokens) {
        return Math.floorDiv((long) inputTokens * rate.inputMicrosPerMtok(), MICROS_PER_MTOK_DIVISOR)
                + Math.floorDiv((long) outputTokens * rate.outputMicrosPerMtok(), MICROS_PER_MTOK_DIVISOR);
    }
}
