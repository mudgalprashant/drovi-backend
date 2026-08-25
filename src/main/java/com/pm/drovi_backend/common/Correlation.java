package com.pm.drovi_backend.common;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Puts a correlation id on work that did not arrive over HTTP.
 *
 * <p>{@link CorrelationIdFilter} covers requests. Everything else the system does — a generation
 * job, the request-log purge, the stuck-job sweeper — runs on a scheduler with an empty MDC, so
 * its log lines were the only ones with nothing to group them by. For a generation that matters
 * most: it is five or six jobs and a dozen model calls, and reconstructing one meant reading
 * timestamps.
 *
 * <p>For a job the id is the <strong>job's own id</strong>, not a fresh one. That is the whole
 * value: the string in the logs is the string in {@code generation_job}, so a user's failed
 * generation and its log lines are found with the same query.
 */
public final class Correlation {

    private Correlation() {
    }

    /** Runs {@code work} with {@code id} as the correlation id, restoring whatever was there. */
    public static void as(String id, Runnable work) {
        get(id, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T get(String id, Supplier<T> work) {
        String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
        MDC.put(CorrelationIdFilter.MDC_KEY, id);
        try {
            return work.get();
        } finally {
            // Restored rather than removed. Scheduled work runs on pooled and virtual threads
            // that are reused, and clearing unconditionally would strip the id from whatever
            // called us — which on a virtual thread carrier can be an unrelated request.
            if (previous == null) {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            } else {
                MDC.put(CorrelationIdFilter.MDC_KEY, previous);
            }
        }
    }

    /** For scheduled work that has no natural id of its own. */
    public static void as(Runnable work) {
        as(UUID.randomUUID().toString(), work);
    }
}
