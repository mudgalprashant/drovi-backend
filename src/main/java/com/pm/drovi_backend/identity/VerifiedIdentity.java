package com.pm.drovi_backend.identity;

/**
 * What a verified token told us about its bearer.
 *
 * <p>Deliberately not a Firebase type: it keeps the identity provider at the edge, so
 * swapping or adding one later touches the verifier and nothing else. Email and display
 * name are nullable — phone-only and anonymous sign-in are legitimate.
 */
public record VerifiedIdentity(String firebaseUid, String email, String displayName) {
}
