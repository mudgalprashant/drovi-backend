package com.pm.drovi_backend.ai;

import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The three things that can stop a model call, checked <em>before</em> it is made.
 *
 * <p>Order matters and is cheapest-first: the kill switch is a cached config read, the two
 * caps are aggregate queries. An incident is exactly when you do not want the kill switch
 * to be gated behind a database round trip.
 *
 * <p>All three controls live in {@code app_config} rather than in this file, so an operator
 * can stop spending at 3am with an UPDATE. That is the reason invariant 3 puts them in the
 * database — code that can only be changed by a deploy is not a kill switch.
 *
 * <h2>Why this is a ceiling, not a budget</h2>
 *
 * The caps are checked against spend <em>already ledgered</em>, so a call that starts under
 * the cap is allowed to finish above it — one call's worth of overshoot, bounded by
 * {@code max_output_tokens}. Reserving an estimate up front would need a tokeniser we do
 * not have and would refuse work on a guess. Set the cap below what you can afford to lose,
 * not at it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpendGuard {

    private static final boolean ENABLED_BY_DEFAULT = false;
    private static final int PLATFORM_CAP_DEFAULT_MICROS = 0;
    private static final int ACCOUNT_CAP_DEFAULT_MICROS = 0;

    private final AppConfigService config;
    private final AiCallLedger ledger;

    /**
     * @throws AiCappedException naming which control refused, for the log and the ledger row
     */
    public void requireSpendAllowed(UUID accountId) {
        // Defaults are OFF and ZERO, not on and generous. A missing or fat-fingered config
        // row must fail closed: the failure mode of the other direction is an uncapped bill,
        // and nobody notices a cap that quietly stopped applying.
        if (!config.getBoolean("ai.enabled", ENABLED_BY_DEFAULT)) {
            throw refuse(AiCappedException.Reason.KILL_SWITCH, accountId, 0, 0);
        }

        long platformCap = config.getInt("ai.daily.cost.cap.micros", PLATFORM_CAP_DEFAULT_MICROS);
        long platformSpend = ledger.spentTodayMicros();
        if (platformSpend >= platformCap) {
            throw refuse(AiCappedException.Reason.PLATFORM_DAILY_CAP, accountId, platformSpend, platformCap);
        }

        long accountCap = config.getInt("ai.account.daily.cost.cap.micros", ACCOUNT_CAP_DEFAULT_MICROS);
        long accountSpend = ledger.spentTodayMicros(accountId);
        if (accountSpend >= accountCap) {
            throw refuse(AiCappedException.Reason.ACCOUNT_DAILY_CAP, accountId, accountSpend, accountCap);
        }
    }

    private AiCappedException refuse(AiCappedException.Reason reason, UUID accountId, long spent, long cap) {
        log.warn("ai.capped reason={} accountId={} spentMicros={} capMicros={}", reason, accountId, spent, cap);
        return new AiCappedException(reason);
    }
}
