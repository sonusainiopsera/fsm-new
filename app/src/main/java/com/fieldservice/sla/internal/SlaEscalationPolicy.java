package com.fieldservice.sla.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code sla_escalation_policy} table.
 *
 * <p>Defines who is notified, on which channels, when a given event type fires for a
 * given priority, plus quiet-hours configuration and the manager grace-period minutes.
 *
 * <p>Package-private; external code uses {@link SlaEscalationPolicyResolver}.
 */
@Entity
@Table(name = "sla_escalation_policy")
class SlaEscalationPolicy {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "priority", nullable = false, length = 10)
    private String priority;

    @Column(name = "recipient_roles", columnDefinition = "text[]")
    private String[] recipientRoles;

    @Column(name = "channels", columnDefinition = "text[]")
    private String[] channels;

    @Column(name = "manager_grace_minutes", nullable = false)
    private int managerGraceMinutes;

    @Column(name = "quiet_hours_start")
    private Integer quietHoursStart;

    @Column(name = "quiet_hours_end")
    private Integer quietHoursEnd;

    @Column(name = "quiet_hours_zone", nullable = false, length = 50)
    private String quietHoursZone = "UTC";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SlaEscalationPolicy() {}

    UUID getId() { return id; }
    String getEventType() { return eventType; }
    String getPriority() { return priority; }
    String[] getRecipientRoles() { return recipientRoles != null ? recipientRoles : new String[0]; }
    String[] getChannels() { return channels != null ? channels : new String[0]; }
    int getManagerGraceMinutes() { return managerGraceMinutes; }
    Integer getQuietHoursStart() { return quietHoursStart; }
    Integer getQuietHoursEnd() { return quietHoursEnd; }
    String getQuietHoursZone() { return quietHoursZone; }
    boolean isActive() { return active; }
    Instant getEffectiveFrom() { return effectiveFrom; }
    Instant getEffectiveTo() { return effectiveTo; }
}
