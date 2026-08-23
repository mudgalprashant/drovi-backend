package com.pm.drovi_backend.ai;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import lombok.Getter;

/**
 * Generation cannot run because the platform is not configured to run it — no active
 * provider row, no adapter behind the name it gives, or no API key in the environment.
 *
 * <p>Distinct from {@link AiCappedException}: that one means the controls did their job,
 * this one means somebody has to go and fix configuration.
 *
 * <p>Two messages, deliberately. The one the caller sees says nothing about our
 * configuration; {@link #getOperatorDetail()} names the exact row or variable and is logged.
 * "Provider GEMINI needs DROVI_GEMINI_API_KEY" is precisely the sentence that makes an
 * operator's afternoon short and an attacker's reconnaissance easy.
 *
 * <p>No {@code ai_call} row is written for these. Nothing was called, and
 * {@code ai_call.provider_code} is a NOT NULL foreign key — ledgering calls that never
 * happened would dilute the one table spend is judged from.
 */
@Getter
public class AiUnavailableException extends DroviException {

    private static final String PUBLIC_MESSAGE = "Sandbox generation is not available right now.";

    private final String operatorDetail;

    public AiUnavailableException(String operatorDetail) {
        super(ErrorCode.AI_UNAVAILABLE, PUBLIC_MESSAGE);
        this.operatorDetail = operatorDetail;
    }
}
