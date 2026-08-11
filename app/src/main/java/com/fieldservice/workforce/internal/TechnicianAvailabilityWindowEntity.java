package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Recurring weekly availability window for a technician.
 * dayOfWeek: 1=Monday .. 7=Sunday (ISO-8601).
 * Windows spanning midnight (endTime <= startTime) are rejected at the service layer.
 */
@Audited
@Entity
@Table(name = "technician_availability_window")
class TechnicianAvailabilityWindowEntity {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected TechnicianAvailabilityWindowEntity() {}

    TechnicianAvailabilityWindowEntity(UUID technicianId, int dayOfWeek,
                                       LocalTime startTime, LocalTime endTime,
                                       LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.id            = UuidV7.generate();
        this.technicianId  = technicianId;
        this.dayOfWeek     = (short) dayOfWeek;
        this.startTime     = startTime;
        this.endTime       = endTime;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo   = effectiveTo;
    }

    UUID      getId()           { return id; }
    UUID      getTechnicianId() { return technicianId; }
    int       getDayOfWeek()    { return dayOfWeek; }
    LocalTime getStartTime()    { return startTime; }
    LocalTime getEndTime()      { return endTime; }
    LocalDate getEffectiveFrom(){ return effectiveFrom; }
    LocalDate getEffectiveTo()  { return effectiveTo; }
    Instant   getCreatedAt()    { return createdAt; }
    Integer   getVersion()      { return version; }
}
