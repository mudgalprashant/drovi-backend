package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A key the sandbox demands of its own callers.
 *
 * <p>There is no field for the key itself, and there never will be. Only the hash and a
 * short display prefix are stored, so Drovi <em>cannot</em> show a key twice — that is the
 * design, not a gap. Users reuse keys across systems, and a leaked backup of a mock service
 * would otherwise be a leaked credential.
 */
@Entity
@Table(name = "project_api_key")
@Getter
public class ProjectApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    /** Enough to tell two keys apart in a list without revealing either. */
    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ProjectApiKey() {
    }

    public static ProjectApiKey issue(UUID projectId, String name, String keyPrefix, String keyHash) {
        ProjectApiKey key = new ProjectApiKey();
        key.projectId = projectId;
        key.name = name;
        key.keyPrefix = keyPrefix;
        key.keyHash = keyHash;
        return key;
    }

    /** Revoked, never deleted: {@code mock_request_log} rows reference this row. */
    public void revoke(Instant when) {
        if (revokedAt == null) {
            this.revokedAt = when;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
