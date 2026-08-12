package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.sla.SlaBreachReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable SLA breach record — written once at detection, enriched by attribution and closure.
 *
 * <p>Envers {@code @Audited}: every change (attribution, finalisation) creates a new revision
 * preserving the prior value with actor and timestamp. No delete path exists on the repository
 * interface; breach records are append-and-revise only.
 *
 * <p>The unique index {@code uq_sla_breach_per_type} on {@code (work_order_id, breach_type)}
 * makes detection idempotent — a second insert for the same pair is blocked at the database.
 */
@Audited
@Entity
@Table(name = "sla_breach")
class SlaBreachEntity {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    /** Either {@code RESPONSE} or {@code RESOLUTION}. */
    @Column(name = "breach_type", nullable = false, updatable = false, length = 20)
    private String breachType;

    /** Effective deadline (pause-adjusted) used to compute overrun. */
    @Column(name = "effective_deadline", nullable = false, updatable = false)
    private Instant effectiveDeadline;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    /** Overrun at detection instant; clamped at zero (negative raw values are anomalies). */
    @Column(name = "overrun_minutes", nullable = false, updatable = false)
    private long overrunMinutes;

    /** Accumulated clock-pausing hold time excluded from overrun calculation. */
    @Column(name = "paused_minutes_excluded", nullable = false, updatable = false)
    private long pausedMinutesExcluded;

    /**
     * Finalised overrun written at closure. NULL until the work order transitions to
     * CLOSED or CANCELLED. Written once; subsequent calls to {@link #finalise} are no-ops.
     */
    @Column(name = "final_overrun_minutes")
    private Long finalOverrunMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 60)
    private SlaBreachReasonCode reasonCode;

    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    @Column(name = "attributed_by")
    private UUID attributedBy;

    @Column(name = "attributed_at")
    private Instant attributedAt;

    @Version
    private Integer version;

    protected SlaBreachEntity() {}

    static SlaBreachEntity create(UUID workOrderId, String breachType,
                                   Instant effectiveDeadline, Instant detectedAt,
                                   long overrunMinutes, long pausedMinutesExcluded) {
        SlaBreachEntity e = new SlaBreachEntity();
        e.id                    = UuidV7.generate();
        e.workOrderId           = workOrderId;
        e.breachType            = breachType;
        e.effectiveDeadline     = effectiveDeadline;
        e.detectedAt            = detectedAt;
        e.overrunMinutes        = overrunMinutes;
        e.pausedMinutesExcluded = pausedMinutesExcluded;
        return e;
    }

    /** Attributes a reason code to the breach. Creates a new Envers revision. */
    void attribute(SlaBreachReasonCode reasonCode, String reasonNote,
                   UUID attributedBy, Instant attributedAt) {
        this.reasonCode    = reasonCode;
        this.reasonNote    = reasonNote;
        this.attributedBy  = attributedBy;
        this.attributedAt  = attributedAt;
    }

    /** Writes the final overrun once; subsequent calls are no-ops (idempotent). */
    void finalise(long finalOverrunMinutes) {
        if (this.finalOverrunMinutes == null) {
            this.finalOverrunMinutes = finalOverrunMinutes;
        }
    }

    UUID                getId()                   { return id; }
    UUID                getWorkOrderId()           { return workOrderId; }
    String              getBreachType()            { return breachType; }
    Instant             getEffectiveDeadline()     { return effectiveDeadline; }
    Instant             getDetectedAt()            { return detectedAt; }
    long                getOverrunMinutes()        { return overrunMinutes; }
    long                getPausedMinutesExcluded() { return pausedMinutesExcluded; }
    Long                getFinalOverrunMinutes()   { return finalOverrunMinutes; }
    SlaBreachReasonCode getReasonCode()            { return reasonCode; }
    String              getReasonNote()            { return reasonNote; }
    UUID                getAttributedBy()          { return attributedBy; }
    Instant             getAttributedAt()          { return attributedAt; }
    Integer             getVersion()               { return version; }
}
