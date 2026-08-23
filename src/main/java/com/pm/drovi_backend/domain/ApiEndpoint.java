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

    /**
     * {@code specificity} is deliberately absent: it is a generated column, computed by the
     * database from {@code path_template}. It decides which template wins when two match,
     * and generating it is what stops a writer getting that ordering wrong.
     */
    public static ApiEndpoint create(UUID projectId, UUID collectionId, String method,
                                     String pathTemplate, String summary, String description,
                                     Behavior behavior, UUID dataCollectionId, String keyParam,
                                     Map<String, Object> requestSchema, Map<String, Object> responseSchema,
                                     Map<String, Object> responseTemplate, Integer successStatus) {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.projectId = projectId;
        endpoint.collectionId = collectionId;
        endpoint.method = method;
        endpoint.pathTemplate = pathTemplate;
        endpoint.summary = summary;
        endpoint.description = description;
        endpoint.behavior = behavior == null ? Behavior.STATIC : behavior;
        endpoint.dataCollectionId = dataCollectionId;
        endpoint.keyParam = keyParam;
        endpoint.requestSchema = requestSchema == null ? Map.of() : requestSchema;
        endpoint.responseSchema = responseSchema == null ? Map.of() : responseSchema;
        endpoint.responseTemplate = responseTemplate == null ? Map.of() : responseTemplate;
        endpoint.successStatus = successStatus == null ? 200 : successStatus;
        return endpoint;
    }

    /**
     * Sparse update. {@code method} and {@code pathTemplate} are editable because a
     * generated spec often gets a path slightly wrong, and correcting it must not mean
     * deleting and recreating the endpoint along with its rules.
     */
    public void update(String method, String pathTemplate, String summary, String description,
                       Behavior behavior, UUID dataCollectionId, String keyParam,
                       Map<String, Object> responseTemplate, Integer successStatus) {
        if (method != null) {
            this.method = method;
        }
        if (pathTemplate != null) {
            this.pathTemplate = pathTemplate;
        }
        if (summary != null) {
            this.summary = summary;
        }
        if (description != null) {
            this.description = description;
        }
        if (behavior != null) {
            this.behavior = behavior;
        }
        if (dataCollectionId != null) {
            this.dataCollectionId = dataCollectionId;
        }
        if (keyParam != null) {
            this.keyParam = keyParam;
        }
        if (responseTemplate != null) {
            this.responseTemplate = responseTemplate;
        }
        if (successStatus != null) {
            this.successStatus = successStatus;
        }
    }
}
