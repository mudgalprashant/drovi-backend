package com.pm.drovi_backend.generation;

import lombok.Getter;

/**
 * The attempt failed in a way no retry would fix, so the remaining attempts are not spent.
 *
 * <p>A model that declined is the clearest case: asking again spends money to be told no a
 * second time. A prompt that cannot produce a valid spec is another — the failure is in the
 * request, and the request is not going to change between attempts.
 *
 * <p>{@code errorCode} lands in {@code generation_job.error_code} and is what a console will
 * switch on, so it is a stable string rather than a sentence.
 */
@Getter
public class TerminalJobException extends RuntimeException {

    private final String errorCode;

    public TerminalJobException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TerminalJobException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
