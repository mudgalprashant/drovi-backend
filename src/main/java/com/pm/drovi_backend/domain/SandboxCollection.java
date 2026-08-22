package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A named set of records inside one project — customers, cards, transactions.
 *
 * <p>{@link #recordCount} and {@link #storedBytes} are maintained by a database trigger and
 * must never be written from Java. They are what quota is enforced against, and a counter
 * that only updates when the service remembers to is a counter that drifts.
 */
@Entity
@Table(name = "sandbox_collection")
@Getter
public class SandboxCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "record_schema", nullable = false)
    private Map<String, Object> recordSchema;

    @Column(name = "validate_on_write", nullable = false)
    private boolean validateOnWrite;

    /** Field inside {@code data} whose value becomes the record key. */
    @Column(name = "key_field", nullable = false)
    private String keyField = "id";

    @Column(name = "record_count", nullable = false, insertable = false, updatable = false)
    private long recordCount;

    @Column(name = "stored_bytes", nullable = false, insertable = false, updatable = false)
    private long storedBytes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SandboxCollection() {
    }
}
