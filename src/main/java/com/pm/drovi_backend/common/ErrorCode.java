package com.pm.drovi_backend.common;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes for the <em>console</em> API.
 *
 * <p>These are deliberately NOT the codes the mock runtime emits. A sandbox speaks the
 * shape of the product it imitates and reports Drovi's own failures with its own small set
 * of platform codes; the console speaks Drovi's house style. Merging the two vocabularies
 * would make it impossible for a caller to tell "Drovi broke" from "the sandbox did what
 * you asked" — see docs/03-api/sandbox-surface.md.
 *
 * <p>The string is the contract. Renaming one breaks every client that switched on it, so
 * add a new code rather than repurposing an old one.
 */
public enum ErrorCode {

    UNAUTHENTICATED("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED),
    /**
     * The server cannot verify tokens at all — no Firebase project is configured. Distinct
     * from {@link #UNAUTHENTICATED} because it is <em>our</em> misconfiguration, not the
     * caller's missing credential, and the fix is an env var rather than a login.
     */
    AUTH_NOT_CONFIGURED("AUTH_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE),
    FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", HttpStatus.NOT_FOUND),
    VALIDATION_FAILED("VALIDATION_FAILED", HttpStatus.BAD_REQUEST),
    CONFLICT("CONFLICT", HttpStatus.CONFLICT),
    QUOTA_EXCEEDED("QUOTA_EXCEEDED", HttpStatus.INSUFFICIENT_STORAGE),
    INTERNAL("INTERNAL", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final HttpStatus status;

    ErrorCode(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
