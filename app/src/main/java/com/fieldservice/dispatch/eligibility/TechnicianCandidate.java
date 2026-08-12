package com.fieldservice.dispatch.eligibility;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable dispatch-owned snapshot of a technician candidate loaded for eligibility
 * evaluation.  Contains only the data needed by EligibilityFilter — no PII beyond
 * the opaque UUID.
 */
record TechnicianCandidate(
        UUID id,
        boolean active,
        String timezone,
        List<CertificationRecord> certifications,
        List<AvailabilityWindow> availabilityWindows,
        List<Absence> absences,
        Double homeLatitude,
        Double homeLongitude
) {
    TechnicianCandidate {
        certifications    = certifications    == null ? List.of() : List.copyOf(certifications);
        availabilityWindows = availabilityWindows == null ? List.of() : List.copyOf(availabilityWindows);
        absences          = absences          == null ? List.of() : List.copyOf(absences);
    }

    /**
     * Recurring weekly availability window.  dayOfWeek: 1=Monday..7=Sunday (ISO-8601).
     */
    record AvailabilityWindow(
            int       dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {}

    /** Approved absence interval — closed on both ends. */
    record Absence(Instant startsAt, Instant endsAt) {}
}
