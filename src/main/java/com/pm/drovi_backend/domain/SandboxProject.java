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

    /**
     * A project created through the console is {@link Status#READY} immediately: it has no
     * endpoints yet, so it answers 404 to everything, which is honest. Generation creates
     * DRAFT projects instead and promotes them when a spec lands.
     */
    public static SandboxProject create(UUID accountId, String projectKey, String name,
                                        String sourceProduct, String sourceDocsUrl, AuthMode authMode) {
        SandboxProject project = new SandboxProject();
        project.accountId = accountId;
        project.projectKey = projectKey;
        project.name = name;
        project.sourceProduct = sourceProduct;
        project.sourceDocsUrl = sourceDocsUrl;
        project.authMode = authMode;
        project.status = Status.READY;
        return project;
    }

    /** Null fields are left alone, so PATCH is sparse rather than a full replace. */
    public void update(String name, SandboxProject.AuthMode authMode,
                       String authHeaderName, Integer latencyMs) {
        if (name != null) {
            this.name = name;
        }
        if (authMode != null) {
            this.authMode = authMode;
        }
        if (authHeaderName != null) {
            this.authHeaderName = authHeaderName;
        }
        if (latencyMs != null) {
            this.latencyMs = latencyMs;
        }
    }

    /**
     * Soft, deliberately. The sandbox stops serving immediately, but the project's records
     * and request log survive — an archive that destroyed data would make "archive" a word
     * nobody dares click.
     */
    public void archive(Instant when) {
        this.archivedAt = when;
        this.status = Status.ARCHIVED;
    }

    public boolean isServing() {
        return status == Status.READY && archivedAt == null;
    }
}
