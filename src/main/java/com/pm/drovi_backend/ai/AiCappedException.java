package com.pm.drovi_backend.ai;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import lombok.Getter;

/**
 * A control did its job: the kill switch is off, or a daily spend cap is reached.
 *
 * <p>This is a normal, expected outcome, not an incident — which is why it is thrown rather
 * than returned as a boolean. A cap check whose result can be ignored is a cap check that
 * will be, and these caps bound the damage of a stolen account and an injection loop, not
 * just the bill.
 *
 * <p>{@link Reason} is for the log and the operator; the caller is told only that generation
 * is paused, because "the platform has spent its daily budget" tells a stranger exactly when
 * to come back and how much it takes to exhaust us.
 */
@Getter
public class AiCappedException extends DroviException {

    public enum Reason {
        /** {@code app_config.ai.enabled} is false. */
        KILL_SWITCH,
        /** {@code ai.daily.cost.cap.micros} reached across all accounts. */
        PLATFORM_DAILY_CAP,
        /** {@code ai.account.daily.cost.cap.micros} reached by this account. */
        ACCOUNT_DAILY_CAP
    }

    private static final String PUBLIC_MESSAGE =
            "Sandbox generation is paused right now. Your existing sandboxes are unaffected.";

    private final Reason reason;

    public AiCappedException(Reason reason) {
        super(ErrorCode.AI_CAPPED, PUBLIC_MESSAGE);
        this.reason = reason;
    }
}
