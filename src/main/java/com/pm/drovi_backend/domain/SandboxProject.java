package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One sandbox: one base URL a caller pastes over their production URL.
 *
 * <p>{@link #projectKey} is the public half of that URL and is unguessable rather than
 * sequential, because when {@link AuthMode#NONE} is in force it is the only thing between
 * the internet and somebody else's pretend production data.
 */
@Entity
@Table(name = "sandbox_project")
@Getter
public class SandboxProject {

    public enum Status { DRAFT, GENERATING, READY, FAILED, ARCHIVED }

    /** How the sandbox authenticates ITS callers — mirroring the product it imitates. */
    public enum AuthMode { NONE, BEARER, HEADER_KEY, BASIC }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_key", nullable = false)
    private String projectKey;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_product", nullable = false)
    private String sourceProduct;

    @Column(name = "source_docs_url")
    private String sourceDocsUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false)
    private AuthMode authMode = AuthMode.BEARER;

    @Column(name = "auth_header_name", nullable = false)
    private String authHeaderName = "Authorization";

    /** Applied to every response, so a caller can reproduce the real product's latency. */
    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SandboxProject() {
    }

    public boolean isServing() {
        return status == Status.READY && archivedAt == null;
    }
}
