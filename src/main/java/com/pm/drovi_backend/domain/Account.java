package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A local identity for a Firebase user.
 *
 * <p>This table exists only so foreign keys have something to point at. Firebase owns
 * authentication: we hold no password, mint no token and store no session, which is a
 * large amount of security-critical code we deliberately do not own.
 */
@Entity
@Table(name = "accounts")
@Getter
public class Account {

    public enum Status { ACTIVE, SUSPENDED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Immutable, issued by Firebase, and the only identity claim we trust. */
    @Column(name = "firebase_uid", nullable = false, updatable = false)
    private String firebaseUid;

    /**
     * Nullable on purpose: Firebase supports phone-only and anonymous sign-in, so an
     * account can legitimately exist with no email. Anything requiring one must check.
     */
    @Column
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "plan_code", nullable = false)
    private String planCode = "FREE";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Account() {
    }

    static Account provision(String firebaseUid, String email, String displayName) {
        Account account = new Account();
        account.firebaseUid = firebaseUid;
        account.email = email;
        account.displayName = displayName;
        return account;
    }

    /** Public factory so the identity package can provision without exposing setters. */
    public static Account of(String firebaseUid, String email, String displayName) {
        return provision(firebaseUid, email, displayName);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /**
     * Refreshes the profile from the token's claims. Firebase is authoritative for these,
     * so a changed email there must not leave a stale copy here.
     */
    public void touch(String email, String displayName, Instant seenAt) {
        this.email = email;
        this.displayName = displayName;
        this.lastSeenAt = seenAt;
    }
}
