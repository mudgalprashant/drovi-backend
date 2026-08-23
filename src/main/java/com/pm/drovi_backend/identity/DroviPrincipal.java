package com.pm.drovi_backend.identity;

import java.util.UUID;

/**
 * The authenticated caller of the console API, resolved once per request.
 *
 * <p>Carries the local {@code accountId} rather than only the Firebase uid, because every
 * ownership check is a query scoped by account — and a check that has to look the account
 * up first is a check somebody will skip.
 *
 * <p>{@code active} is carried rather than enforced here: authentication answers "who is
 * this", authorization answers "may they". Conflating them by rejecting inside the token
 * converter throws from the filter chain, where the exception handler cannot reach it —
 * so a suspended account leaves as an unhandled 500 instead of a clean 403.
 */
public record DroviPrincipal(UUID accountId, String firebaseUid, String email, boolean active) {

    /** The authority every usable account holds. A suspended one holds none. */
    public static final String ACTIVE_AUTHORITY = "ACCOUNT_ACTIVE";
}
