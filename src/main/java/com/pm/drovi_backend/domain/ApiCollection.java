package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A Postman-style folder of endpoints — "Cards", "Transactions", "Webhooks".
 *
 * <p>Called an <b>API group</b> everywhere outside the schema. "Collection" is ambiguous in
 * this product: it also means a set of stored records ({@link SandboxCollection}), and the
 * two are unrelated. Never write a bare "collection" in code, docs or a UI label.
 */
@Entity
@Table(name = "api_collection")
@Getter
public class ApiCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ApiCollection() {
    }

    public static ApiCollection create(UUID projectId, String name, String description) {
        ApiCollection group = new ApiCollection();
        group.projectId = projectId;
        group.name = name;
        group.description = description;
        return group;
    }

    public void update(String name, String description) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
    }
}
