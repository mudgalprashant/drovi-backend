package com.pm.drovi_backend.ai;

/**
 * How a model call ended. Mirrors the {@code CHECK} constraint on {@code ai_call.status}.
 *
 * <p>Every one of these is ledgered, including the ones where no request left the process.
 * A ledger that records only successes under-reports exactly when spend is running away,
 * and a {@link #CAPPED} row is the evidence that a control worked.
 */
public enum AiCallStatus {

    /** The provider answered. */
    OK,
    /** The provider was reached and failed, or answered something unusable. */
    ERROR,
    /** The call was abandoned at the read timeout. It may still have cost money. */
    TIMEOUT,
    /** The model declined to answer. Not a bug — a content decision by the provider. */
    REFUSED,
    /** Refused by us before the call: the kill switch is off, or a daily cap is spent. */
    CAPPED
}
