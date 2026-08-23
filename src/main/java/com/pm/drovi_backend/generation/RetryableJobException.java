package com.pm.drovi_backend.generation;

/**
 * The attempt failed in a way another attempt could fix.
 *
 * <p>The canonical case is unparseable model output: models are not deterministic, and asking
 * again is both cheap and likely to work. Bounded by {@code ai.max.attempts}, so "likely to
 * work" cannot become "forever".
 *
 * <p>The message is for the log and for the job's {@code error_message}, so it must be ours
 * and safe to show — never an upstream provider's text.
 */
public class RetryableJobException extends RuntimeException {

    public RetryableJobException(String message) {
        super(message);
    }

    public RetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
