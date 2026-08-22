package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One route in the replica, holding the real product's path verbatim — placeholders and
 * all — because storing it exactly is what makes the base URL a drop-in swap.
 *
 * <p>{@link #specificity} is a database-generated column, never set from Java: it decides
 * which template wins when two of them match, and a writer who gets that ordering wrong
 * makes {@code /v1/cards/blocked} unreachable behind {@code /v1/cards/{cardId}}.
 */
@Entity
@Table(name = "api_endpoint")
@Getter
public class ApiEndpoint {

    /**
     * How the endpoint produces a body. Everything but {@link #STATIC} reads or writes
     * {@link #dataCollectionId} — which is invariant 1: behaviour comes from data.
     */
    public enum Behavior { LIST, GET, CREATE, UPDATE, DELETE, STATIC }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Column(nullable = false)
    private String method;

    @Column(name = "path_template", nullable = false)
    private String pathTemplate;

    @Column(name = "operation_id")
    private String operationId;

    @Column(nullable = false)
    private String summary;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Behavior behavior = Behavior.STATIC;

    @Column(name = "data_collection_id")
    private UUID dataCollectionId;

    /** Which path parameter identifies the record, for GET/UPDATE/DELETE. */
    @Column(name = "key_param")
    private String keyParam;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_schema", nullable = false)
    private Map<String, Object> requestSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_schema", nullable = false)
    private Map<String, Object> responseSchema;

    /** The envelope. Placeholders are substituted by the runtime — see TemplateRenderer. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_template", nullable = false)
    private Map<String, Object> responseTemplate;

    @Column(name = "success_status", nullable = false)
    private int successStatus = 200;

    @Column(insertable = false, updatable = false)
    private int specificity;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ApiEndpoint() {
    }
}
