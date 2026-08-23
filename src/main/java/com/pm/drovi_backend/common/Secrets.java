package com.pm.drovi_backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generating and hashing the credentials Drovi issues.
 *
 * <p>Both halves live here on purpose. Issuing hashes a key one way and verifying hashes it
 * another is a bug that presents as "my key stopped working" and is invisible in review —
 * so there is exactly one implementation, used by both sides.
 */
public final class Secrets {

    /** Seeded once by the OS; a shared instance is thread-safe and avoids reseeding cost. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /**
     * A URL-safe random token.
     *
     * @param bytes entropy, not output length. 24 bytes is 192 bits — far past guessing,
     *              and short enough to paste. Never lower this for cosmetic reasons: for a
     *              project key with {@code auth_mode = NONE} this value is the only thing
     *              between the internet and someone's sandbox.
     */
    public static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** The stored form of an issued key. The raw value is never persisted. */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
