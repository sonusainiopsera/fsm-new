package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Recurring weekly working window for a technician. Package-private. */
@Audited
@Entity
@Table(name = "technician_availability_window")
class TechnicianAvailabilityWindowEntity extends BaseEntity {

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    /** ISO-8601 day of week: 1 = MONDAY, 7 = SUNDAY. */
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

    protected TechnicianAvailabilityWindowEntity() {}

    TechnicianAvailabilityWindowEntity(UUID technicianId, short dayOfWeek,
                                        LocalTime startTime, LocalTime endTime,
                                        LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.technicianId  = technicianId;
        this.dayOfWeek     = dayOfWeek;
        this.startTime     = startTime;
        this.endTime       = endTime;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo   = effectiveTo;
    }

    UUID getTechnicianId()         { return technicianId; }
    short getDayOfWeek()           { return dayOfWeek; }
    void setDayOfWeek(short d)     { this.dayOfWeek = d; }
    LocalTime getStartTime()       { return startTime; }
    void setStartTime(LocalTime t) { this.startTime = t; }
    LocalTime getEndTime()         { return endTime; }
    void setEndTime(LocalTime t)   { this.endTime = t; }
    LocalDate getEffectiveFrom()   { return effectiveFrom; }
    void setEffectiveFrom(LocalDate d) { this.effectiveFrom = d; }
    LocalDate getEffectiveTo()     { return effectiveTo; }
    void setEffectiveTo(LocalDate d)   { this.effectiveTo = d; }
}
