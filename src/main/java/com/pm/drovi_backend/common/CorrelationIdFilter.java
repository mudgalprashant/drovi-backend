package com.pm.drovi_backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request an id that appears in its logs, its error body and its response
 * header, so a user reporting a failure hands over one string that finds everything.
 *
 * <p>Runs first, before authentication: a request that is rejected at the door is exactly
 * the one someone will ask about.
 *
 * <p>An inbound id is honoured so a chain of calls shares one id, but it is length-capped
 * and sanitised — it lands in log lines, and an unbounded caller-controlled string in a
 * log is both a forgery and a log-injection vector.
 */
@Component
@Order(Integer.MIN_VALUE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled and virtual threads are reused by the carrier;
            // leaving the MDC populated attributes the next request's logs to this one.
            MDC.remove(MDC_KEY);
        }
    }

    /** Returns the caller's id if it is plausibly one of ours, otherwise a fresh one. */
    private static String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (!allowed) {
                return UUID.randomUUID().toString();
            }
        }
        return candidate;
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "unknown" : id;
    }
}
