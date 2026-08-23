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

    public static ResponseRule create(UUID projectId, UUID endpointId, String name, Integer priority,
                                      Map<String, Object> matcher, Integer statusCode,
                                      Map<String, Object> headers, Map<String, Object> body,
                                      Integer delayMs, Integer remainingUses, Instant expiresAt) {
        ResponseRule rule = new ResponseRule();
        rule.projectId = projectId;
        rule.endpointId = endpointId;
        rule.name = name;
        rule.priority = priority == null ? 100 : priority;
        // An absent matcher means "match everything" -- that is how "always return 503" is
        // written, and it must stay the explicit default rather than a surprise.
        rule.matcher = matcher == null ? Map.of() : matcher;
        rule.statusCode = statusCode == null ? 200 : statusCode;
        rule.headers = headers == null ? Map.of() : headers;
        rule.body = body;
        rule.delayMs = delayMs == null ? 0 : delayMs;
        rule.remainingUses = remainingUses;
        rule.expiresAt = expiresAt;
        return rule;
    }

    public void update(String name, Integer priority, Boolean enabled, Map<String, Object> matcher,
                       Integer statusCode, Map<String, Object> headers, Map<String, Object> body,
                       Integer delayMs, Integer remainingUses) {
        if (name != null) {
            this.name = name;
        }
        if (priority != null) {
            this.priority = priority;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (matcher != null) {
            this.matcher = matcher;
        }
        if (statusCode != null) {
            this.statusCode = statusCode;
        }
        if (headers != null) {
            this.headers = headers;
        }
        if (body != null) {
            this.body = body;
        }
        if (delayMs != null) {
            this.delayMs = delayMs;
        }
        // Nullable on purpose: setting it back to null makes a one-shot rule permanent
        // again, which is how you "re-arm" a rule without deleting and recreating it.
        this.remainingUses = remainingUses;
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
