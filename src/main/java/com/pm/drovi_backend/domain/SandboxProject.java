package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One sandbox: one base URL a caller pastes over their production URL.
 *
 * <p>{@link #id} is the public half of that URL: {@code /s/{id}/…}. It is a v4 uuid, so
 * unguessable rather than sequential — which matters because when {@link AuthMode#NONE} is in
 * force it is the only thing between the internet and somebody else's pretend production data.
 *
 * <p>It used to be a separate random {@code project_key}. One identifier for one thing is
 * kinder to a user holding both the console URL and the base URL, and 122 random bits is past
 * guessing either way. The consequence to remember: a project id shared in a screenshot or a
 * support thread is now also the sandbox's address.
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

    /**
     * How errors this replica produces <em>in character</em> should look — a missing record, a
     * rejected key, an unmatched route. Rendered by {@code TemplateRenderer} with
     * {@code {{status}}}, {@code {{code}}} and {@code {{message}}}.
     *
     * <p>Null means Drovi's own shape. Drovi's <em>own</em> failures — rate limited, quota
     * exhausted, no such sandbox — never use this: the inspector has to show platform errors as
     * distinct from simulated ones, and a user needs to know which of us is refusing them.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_envelope")
    private Map<String, Object> errorEnvelope;

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
    public static SandboxProject create(UUID accountId, String name,
                                        String sourceProduct, String sourceDocsUrl, AuthMode authMode) {
        SandboxProject project = new SandboxProject();
        project.accountId = accountId;
        project.name = name;
        project.sourceProduct = sourceProduct;
        project.sourceDocsUrl = sourceDocsUrl;
        project.authMode = authMode;
        project.status = Status.READY;
        return project;
    }

    /**
     * Set by generation from what research found about the imitated product. Deliberately not
     * part of {@link #update}: it is a property of the imitation rather than a user preference,
     * and a console field for hand-editing JSON is not the shape of that.
     */
    public void useErrorEnvelope(Map<String, Object> envelope) {
        this.errorEnvelope = envelope == null || envelope.isEmpty() ? null : Map.copyOf(envelope);
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
     * Generation has started. The sandbox stops serving while it runs — {@link #isServing()}
     * requires READY — and that is the point: a project's routes and its data should appear
     * together, not one endpoint at a time as the pipeline gets to them.
     *
     * <p>An archived project is left alone. Generating into something the user has thrown away
     * would quietly bring it back.
     */
    public void markGenerating() {
        if (status != Status.ARCHIVED) {
            this.status = Status.GENERATING;
        }
    }

    /** Generation finished and the sandbox is worth calling. */
    public void markReady() {
        if (status == Status.GENERATING || status == Status.DRAFT) {
            this.status = Status.READY;
        }
    }

    /**
     * Generation gave up. FAILED rather than back to DRAFT, because the two are different
     * things to see in a list: one is a project waiting to be described, the other is one
     * whose description did not work out and which the user may want to retry or delete.
     */
    public void markGenerationFailed() {
        if (status == Status.GENERATING) {
            this.status = Status.FAILED;
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
