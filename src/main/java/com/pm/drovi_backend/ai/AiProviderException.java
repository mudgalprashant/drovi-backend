package com.pm.drovi_backend.ai;

import lombok.Getter;

/**
 * A provider call that did not produce a usable answer.
 *
 * <p>The message is <em>ours</em> and is written to be logged, never returned. Upstream
 * error text is an information-disclosure channel and changes without notice, so it stops
 * here: {@link AiGateway} translates this into a generic failure for the caller.
 */
@Getter
public class AiProviderException extends RuntimeException {

    /** The ledger status this failure should be recorded as. */
    private final AiCallStatus status;

    public AiProviderException(AiCallStatus status, String message) {
        super(message);
        this.status = status;
    }

    public AiProviderException(AiCallStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static AiProviderException error(String message, Throwable cause) {
        return new AiProviderException(AiCallStatus.ERROR, message, cause);
    }

    public static AiProviderException timeout(String message, Throwable cause) {
        return new AiProviderException(AiCallStatus.TIMEOUT, message, cause);
    }

    public static AiProviderException refused(String message) {
        return new AiProviderException(AiCallStatus.REFUSED, message);
    }
}
