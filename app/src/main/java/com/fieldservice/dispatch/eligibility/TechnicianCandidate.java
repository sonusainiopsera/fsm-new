package com.fieldservice.dispatch.eligibility;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a technician's eligibility-relevant attributes.
 *
 * <p>All fields are loaded from the database in a single paginated batch; no lazy
 * loading occurs after construction. This is the sole input to {@link EligibilityFilter}.
 */
record TechnicianCandidate(
        UUID id,
        boolean active,
        String timezone,
        Double homeLatitude,    // null = coordinates unknown — geo check skipped
        Double homeLongitude,   // null = coordinates unknown — geo check skipped
        List<CertSnap> certifications,
        List<WindowSnap> availabilityWindows,
        List<AbsenceSnap> absences
) {

    TechnicianCandidate {
        certifications     = certifications     == null ? List.of() : List.copyOf(certifications);
        availabilityWindows = availabilityWindows == null ? List.of() : List.copyOf(availabilityWindows);
        absences            = absences            == null ? List.of() : List.copyOf(absences);
        if (timezone == null || timezone.isBlank()) timezone = "UTC";
    }

    /** One certification record snapshot — active records only. */
    record CertSnap(
            String typeCode,
            LocalDate expiresOn  // null = perpetual (never expires)
    ) {}

    /** One recurring weekly working window. */
    record WindowSnap(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo   // null = open-ended
    ) {}

    /** One absence period that overlaps the candidate load window. */
    record AbsenceSnap(
            Instant startsAt,
            Instant endsAt
    ) {}
}
