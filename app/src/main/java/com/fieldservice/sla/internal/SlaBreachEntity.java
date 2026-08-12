package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import com.fieldservice.sla.SlaBreachDto;
import com.fieldservice.sla.SlaBreachReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code sla_breach} table.
 *
 * <p>Append-and-revise only: no delete path exists on the repository interface.
 * Envers audits every mutation so attribution corrections are fully traceable.
 */
@Entity
@Table(name = "sla_breach")
@Audited
class SlaBreachEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "breach_type", nullable = false, updatable = false, length = 20)
    private String breachType;

    @Column(name = "effective_deadline", nullable = false, updatable = false)
    private Instant effectiveDeadline;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "overrun_minutes", nullable = false, updatable = false)
    private int overrunMinutes;

    @Column(name = "paused_minutes_excluded", nullable = false, updatable = false)
    private int pausedMinutesExcluded;

    @Column(name = "final_overrun_minutes")
    private Integer finalOverrunMinutes;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    @Column(name = "attributed_by")
    private UUID attributedBy;

    @Column(name = "attributed_at")
    private Instant attributedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected SlaBreachEntity() {}

    /** Factory for creating a new breach record at detection time. */
    static SlaBreachEntity detect(UUID workOrderId, String breachType,
                                   Instant effectiveDeadline, Instant detectedAt,
                                   int overrunMinutes, int pausedMinutesExcluded) {
        SlaBreachEntity e = new SlaBreachEntity();
        e.workOrderId           = workOrderId;
        e.breachType            = breachType;
        e.effectiveDeadline     = effectiveDeadline;
        e.detectedAt            = detectedAt;
        e.overrunMinutes        = overrunMinutes;
        e.pausedMinutesExcluded = pausedMinutesExcluded;
        return e;
    }

    void writeFinalOverrun(int finalOverrunMinutes) {
        if (this.finalOverrunMinutes != null) {
            return; // idempotent — never overwrite an already-finalised value
        }
        this.finalOverrunMinutes = finalOverrunMinutes;
    }

    void attributeReason(SlaBreachReasonCode code, String note, UUID attributedBy, Instant attributedAt) {
        this.reasonCode   = code.name();
        this.reasonNote   = note;
        this.attributedBy = attributedBy;
        this.attributedAt = attributedAt;
    }

    SlaBreachDto toDto() {
        return new SlaBreachDto(id, workOrderId, breachType, effectiveDeadline, detectedAt,
                overrunMinutes, pausedMinutesExcluded, finalOverrunMinutes,
                reasonCode, reasonNote, attributedBy, attributedAt);
    }

    UUID getId()                   { return id; }
    UUID getWorkOrderId()          { return workOrderId; }
    String getBreachType()         { return breachType; }
    Instant getEffectiveDeadline() { return effectiveDeadline; }
    Instant getDetectedAt()        { return detectedAt; }
    int getOverrunMinutes()        { return overrunMinutes; }
    int getPausedMinutesExcluded() { return pausedMinutesExcluded; }
    Integer getFinalOverrunMinutes() { return finalOverrunMinutes; }
    String getReasonCode()         { return reasonCode; }
    Integer getVersion()           { return version; }
}
