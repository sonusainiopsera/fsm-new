package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.UuidV7;
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

@Audited
@Entity
@Table(name = "technician_absence")
class TechnicianAbsenceEntity {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 50)
    private AbsenceReason reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected TechnicianAbsenceEntity() {}

    TechnicianAbsenceEntity(UUID technicianId, Instant startsAt, Instant endsAt,
                            AbsenceReason reason) {
        this.id           = UuidV7.generate();
        this.technicianId = technicianId;
        this.startsAt     = startsAt;
        this.endsAt       = endsAt;
        this.reason       = reason;
    }

    UUID          getId()           { return id; }
    UUID          getTechnicianId() { return technicianId; }
    Instant       getStartsAt()     { return startsAt; }
    Instant       getEndsAt()       { return endsAt; }
    AbsenceReason getReason()       { return reason; }
    Instant       getCreatedAt()    { return createdAt; }
    Integer       getVersion()      { return version; }
}
