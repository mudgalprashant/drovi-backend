package com.pm.drovi_backend.runtime;

import com.pm.drovi_backend.common.Secrets;
import com.pm.drovi_backend.domain.SandboxProject;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks the credential the sandbox demands of its own callers.
 *
 * <p>This exists because a drop-in replacement has to reject an unauthenticated call
 * exactly like the product it imitates. If the sandbox waves everything through, the
 * integration under test never exercises its own auth path, and the first time anyone
 * finds out is against production.
 *
 * <p>Only the hash is stored, so a leaked backup of a mock service is not a leaked key.
 */
@Component
@RequiredArgsConstructor
public class SandboxAuthenticator {

    private final JdbcTemplate jdbc;

    /** Present and valid, or empty. The caller decides what a failure means. */
    @Transactional
    public Optional<UUID> authenticate(SandboxProject project, MockRequest request) {
        if (project.getAuthMode() == SandboxProject.AuthMode.NONE) {
            return Optional.of(NO_KEY);
        }
        Optional<String> presented = extract(project, request);
        if (presented.isEmpty()) {
            return Optional.empty();
        }
        // Looked up BY HASH, so the query is constant-work and the raw key never reaches
        // the database, a log, or a slow-query report.
        return jdbc.query("""
                        SELECT id FROM project_api_key
                         WHERE project_id = ? AND key_hash = ? AND revoked_at IS NULL
                        """,
                rs -> {
                    if (!rs.next()) {
                        return Optional.<UUID>empty();
                    }
                    UUID id = rs.getObject(1, UUID.class);
                    jdbc.update("UPDATE project_api_key SET last_used_at = now() WHERE id = ?", id);
                    return Optional.of(id);
                },
                project.getId(), sha256(presented.get()));
    }

    /** Sentinel for "this project requires no key", so callers need no null check. */
    public static final UUID NO_KEY = new UUID(0L, 0L);

    private Optional<String> extract(SandboxProject project, MockRequest request) {
        Optional<String> raw = request.header(project.getAuthHeaderName());
        return switch (project.getAuthMode()) {
            case NONE -> Optional.empty();
            case HEADER_KEY -> raw.map(String::trim).filter(v -> !v.isEmpty());
            case BEARER -> raw.filter(v -> v.regionMatches(true, 0, "Bearer ", 0, 7))
                    .map(v -> v.substring(7).trim())
                    .filter(v -> !v.isEmpty());
            // Basic carries "key:" or "user:key"; the secret is the password half, which
            // is how most products that use Basic actually issue API keys.
            case BASIC -> raw.filter(v -> v.regionMatches(true, 0, "Basic ", 0, 6))
                    .map(v -> v.substring(6).trim())
                    .flatMap(SandboxAuthenticator::decodeBasic);
        };
    }

    private static Optional<String> decodeBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String secret = colon < 0 ? decoded : decoded.substring(colon + 1);
            return secret.isEmpty() ? Optional.empty() : Optional.of(secret);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Delegates so issuing and verifying can never drift apart. */
    static String sha256(String raw) {
        return Secrets.sha256Hex(raw);
    }
}
