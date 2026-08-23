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

    public static SandboxCollection create(UUID projectId, String code, String displayName,
                                           String description, Map<String, Object> recordSchema,
                                           String keyField) {
        SandboxCollection collection = new SandboxCollection();
        collection.projectId = projectId;
        collection.code = code;
        collection.displayName = displayName;
        collection.description = description;
        collection.recordSchema = recordSchema == null ? Map.of() : recordSchema;
        collection.keyField = keyField == null || keyField.isBlank() ? "id" : keyField;
        return collection;
    }

    /**
     * {@code keyField} is deliberately not updatable. Changing it would silently orphan
     * every existing record, because {@code record_key} was extracted using the old one and
     * lookups would start missing rows that are plainly there.
     */
    public void update(String displayName, String description, Map<String, Object> recordSchema) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (description != null) {
            this.description = description;
        }
        if (recordSchema != null) {
            this.recordSchema = recordSchema;
        }
    }
}
