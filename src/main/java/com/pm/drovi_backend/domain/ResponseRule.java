package com.pm.drovi_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The override layer: what the chat writes when the user asks for something the data
 * cannot express — a 429, an outage, a call that fails exactly once.
 *
 * <p>Lowest {@link #priority} wins and the first match stops evaluation. A rule with
 * {@link #remainingUses} set is consumed as it fires, which is how "make the next call
 * fail" is expressed without anyone having to remember to turn it off again.
 */
@Entity
@Table(name = "response_rule")
@Getter
public class ResponseRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Conditions on the request. An empty object matches everything. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> matcher;

    @Column(name = "status_code", nullable = false)
    private int statusCode = 200;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> headers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> body;

    @Column(name = "delay_ms", nullable = false)
    private int delayMs;

    @Column(name = "remaining_uses")
    private Integer remainingUses;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ResponseRule() {
    }

    /** A rule that has run out of uses or aged out is inert, not an error. */
    public boolean isLive(Instant now) {
        if (!enabled) {
            return false;
        }
        if (remainingUses != null && remainingUses <= 0) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
