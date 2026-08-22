package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of a caller's pretend production data.
 *
 * <p>{@link #projectId} is denormalised rather than derived through the collection, and a
 * composite foreign key re-checks it: every runtime query is scoped by project first, so a
 * missing tenant predicate degrades a query instead of silently widening it.
 */
@Entity
@Table(name = "sandbox_record")
@Getter
public class SandboxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    /** The id the CALLER uses: {@code cus_9f2}. Extracted from data on write. */
    @Column(name = "record_key", nullable = false)
    private String recordKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> data;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SandboxRecord() {
    }

    /**
     * The only way to make one. The counters that quota is enforced against are maintained
     * by a trigger on write, so a record must never be assembled field by field and left
     * half-built — every path into this table is a real INSERT.
     */
    public static SandboxRecord create(UUID projectId, UUID collectionId, String recordKey,
                                       Map<String, Object> data) {
        SandboxRecord record = new SandboxRecord();
        record.projectId = projectId;
        record.collectionId = collectionId;
        record.recordKey = recordKey;
        record.data = data;
        return record;
    }

    public void replaceData(Map<String, Object> newData) {
        this.data = newData;
    }
}
