package com.fieldservice.sla.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Data-driven escalation policy row keyed by event type and priority.
 *
 * <p>{@code recipientRoles} and {@code channels} are comma-separated lists of
 * AppRole / NotificationChannel names stored as plain text to avoid coupling to
 * those enums in the persistence layer.
 *
 * <p>{@code quietHoursStart} and {@code quietHoursEnd} are "HH:mm" strings in the
 * configured timezone. Both null means no quiet hours. Breach events bypass quiet hours
 * regardless of this setting.
 *
 * <p>{@code dedupWindowMinutes == 0} means no deduplication (used for breach events).
 */
@Entity
@Table(name = "sla_escalation_policy")
class SlaEscalationPolicy {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 30)
    private String priority;

    @Column(name = "recipient_roles", nullable = false, length = 255)
    private String recipientRoles;

    @Column(nullable = false, length = 100)
    private String channels;

    @Column(name = "manager_grace_minutes", nullable = false)
    private int managerGraceMinutes;

    @Column(name = "quiet_hours_start", length = 5)
    private String quietHoursStart;

    @Column(name = "quiet_hours_end", length = 5)
    private String quietHoursEnd;

    @Column(nullable = false, length = 60)
    private String zone;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "dedup_window_minutes", nullable = false)
    private int dedupWindowMinutes;

    protected SlaEscalationPolicy() {}

    UUID   getId()                  { return id; }
    String getEventType()           { return eventType; }
    String getPriority()            { return priority; }
    String getRecipientRoles()      { return recipientRoles; }
    String getChannels()            { return channels; }
    int    getManagerGraceMinutes() { return managerGraceMinutes; }
    String getQuietHoursStart()     { return quietHoursStart; }
    String getQuietHoursEnd()       { return quietHoursEnd; }
    String getZone()                { return zone; }
    boolean isActive()              { return active; }
    int    getDedupWindowMinutes()  { return dedupWindowMinutes; }
}
