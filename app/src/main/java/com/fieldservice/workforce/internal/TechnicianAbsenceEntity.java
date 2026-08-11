package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/** A dated absence period for a technician with a controlled reason. Package-private. */
@Audited
@Entity
@Table(name = "technician_absence")
class TechnicianAbsenceEntity extends BaseEntity {

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private AbsenceReason reason;

    protected TechnicianAbsenceEntity() {}

    TechnicianAbsenceEntity(UUID technicianId, Instant startsAt, Instant endsAt,
                             AbsenceReason reason) {
        this.technicianId = technicianId;
        this.startsAt     = startsAt;
        this.endsAt       = endsAt;
        this.reason       = reason;
    }

    UUID getTechnicianId()       { return technicianId; }
    Instant getStartsAt()        { return startsAt; }
    Instant getEndsAt()          { return endsAt; }
    AbsenceReason getReason()    { return reason; }
    void setReason(AbsenceReason r) { this.reason = r; }
}
