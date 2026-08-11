package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable structured audit record written in the same transaction as every
 * appearance preference mutation.
 *
 * <p>Captures actor, timestamp, resource, field and before/after values per
 * BR-21. Data classification: Internal (BR-23) — no PII, no Restricted fields.
 */
@Entity
@Table(name = "user_preference_audit")
public class UserPreferenceAudit {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "field_name", nullable = false, length = 100)
    private String fieldName;

    @Column(name = "old_value", length = 50)
    private String oldValue;

    @Column(name = "new_value", length = 50)
    private String newValue;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_role", length = 50)
    private String actorRole;

    @Column(name = "trace_id", length = 36)
    private String traceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected UserPreferenceAudit() {}

    public static UserPreferenceAudit record(
            UUID userId, String fieldName, String oldValue, String newValue,
            UUID actorId, String actorRole, String traceId) {
        UserPreferenceAudit a = new UserPreferenceAudit();
        a.id          = UuidV7.generate();
        a.userId      = userId;
        a.fieldName   = fieldName;
        a.oldValue    = oldValue;
        a.newValue    = newValue;
        a.actorId     = actorId;
        a.actorRole   = actorRole;
        a.traceId     = traceId;
        a.occurredAt  = Instant.now();
        return a;
    }

    public UUID    getId()         { return id; }
    public UUID    getUserId()     { return userId; }
    public String  getFieldName()  { return fieldName; }
    public String  getOldValue()   { return oldValue; }
    public String  getNewValue()   { return newValue; }
    public UUID    getActorId()    { return actorId; }
    public String  getActorRole()  { return actorRole; }
    public String  getTraceId()    { return traceId; }
    public Instant getOccurredAt() { return occurredAt; }
}
