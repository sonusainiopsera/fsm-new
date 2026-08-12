package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure, framework-free eligibility filter.
 *
 * <p>No Spring, JPA, or HTTP dependency. Constructed with plain value objects and
 * an injected Clock so expiry boundaries are deterministically testable.
 *
 * <p>Rules are evaluated in fixed order; the first matching rule is the primary
 * exclusion reason when multiple rules fire simultaneously:
 * <ol>
 *   <li>INACTIVE_TECHNICIAN — technician.active is false</li>
 *   <li>CERTIFICATION_MISSING — no cert record of a required type exists</li>
 *   <li>CERTIFICATION_EXPIRED — cert exists but every instance expired before window start</li>
 *   <li>UNAVAILABLE_IN_WINDOW — no shift covers the window or an absence overlaps it</li>
 *   <li>OUT_OF_REACH — Haversine distance exceeds maxReachKm</li>
 * </ol>
 *
 * <h3>Geographic edge case</h3>
 * When the work order site has no geocoded coordinates ({@code requirements.siteLatitude()}
 * or {@code requirements.siteLongitude()} is null), the reach filter is skipped for
 * every candidate and {@link EligibilityResult#reachUnknown()} is set to true.
 * When a candidate has no home-base coordinates, that individual candidate is also
 * silently excluded from the reach check (not excluded as OUT_OF_REACH).
 */
final class EligibilityFilter {

    private final Clock clock;

    EligibilityFilter(Clock clock) {
        this.clock = clock;
    }

    EligibilityResult filter(List<TechnicianCandidate> candidates,
                             WorkOrderRequirements requirements) {

        boolean siteHasCoords = requirements.siteLatitude()  != null
                             && requirements.siteLongitude() != null;

        List<UUID>             eligible = new ArrayList<>();
        List<ExcludedCandidate> excluded = new ArrayList<>();

        // Cert evaluation date = UTC calendar date of the service window start.
        // A cert with expiresOn < atDate is treated as expired with no grace period.
        LocalDate atDate = LocalDate.ofInstant(
                requirements.serviceWindowStart(), ZoneOffset.UTC);

        Instant windowStart = requirements.serviceWindowStart();
        Instant windowEnd   = requirements.serviceWindowEnd();

        for (TechnicianCandidate candidate : candidates) {

            // Rule 1 — inactive
            if (!candidate.active()) {
                excluded.add(new ExcludedCandidate(candidate.id(), ExclusionReason.INACTIVE_TECHNICIAN));
                continue;
            }

            // Rules 2 & 3 — certifications
            ExclusionReason certReason = evaluateCertifications(
                    candidate.certifications(), requirements.requiredCertificationCodes(), atDate);
            if (certReason != null) {
                excluded.add(new ExcludedCandidate(candidate.id(), certReason));
                continue;
            }

            // Rule 4 — availability
            if (!isAvailable(candidate, windowStart, windowEnd)) {
                excluded.add(new ExcludedCandidate(candidate.id(), ExclusionReason.UNAVAILABLE_IN_WINDOW));
                continue;
            }

            // Rule 5 — geographic reach
            if (siteHasCoords && candidate.homeLatitude() != null && candidate.homeLongitude() != null) {
                double distKm = HaversineDistance.distanceKm(
                        candidate.homeLatitude(), candidate.homeLongitude(),
                        requirements.siteLatitude(), requirements.siteLongitude());
                if (distKm > requirements.maxReachKm()) {
                    excluded.add(new ExcludedCandidate(candidate.id(), ExclusionReason.OUT_OF_REACH));
                    continue;
                }
            }

            eligible.add(candidate.id());
        }

        return new EligibilityResult(eligible, excluded, !siteHasCoords);
    }

    // ---- Certification rules -----------------------------------------------

    private static ExclusionReason evaluateCertifications(
            List<CertificationRecord> certs,
            List<String> required,
            LocalDate atDate) {

        for (String code : required) {
            List<CertificationRecord> matching = certs.stream()
                    .filter(c -> code.equals(c.typeCode()))
                    .toList();

            if (matching.isEmpty()) {
                return ExclusionReason.CERTIFICATION_MISSING;
            }

            // At least one cert of the required type exists.
            // If ANY cert is current (not expired), the requirement is satisfied.
            boolean anyCurrent = matching.stream()
                    .anyMatch(c -> c.expiresOn() == null || !c.expiresOn().isBefore(atDate));

            if (!anyCurrent) {
                return ExclusionReason.CERTIFICATION_EXPIRED;
            }
        }
        return null; // all required certifications satisfied
    }

    // ---- Availability rules ------------------------------------------------

    private static boolean isAvailable(TechnicianCandidate candidate,
                                       Instant windowStart,
                                       Instant windowEnd) {
        List<TechnicianCandidate.AvailabilityWindow> windows  = candidate.availabilityWindows();
        List<TechnicianCandidate.Absence>            absences = candidate.absences();

        if (windows.isEmpty()) {
            return false; // fail-safe: no windows → unavailable
        }

        // Absence overlaps window?
        for (TechnicianCandidate.Absence absence : absences) {
            if (absence.startsAt().isBefore(windowEnd) && absence.endsAt().isAfter(windowStart)) {
                return false;
            }
        }

        // Does any shift window cover the requested service window?
        ZoneId zone;
        try {
            zone = ZoneId.of(candidate.timezone() != null ? candidate.timezone() : "UTC");
        } catch (Exception e) {
            zone = ZoneOffset.UTC;
        }

        LocalDateTime localFrom = LocalDateTime.ofInstant(windowStart, zone);
        LocalDateTime localTo   = LocalDateTime.ofInstant(windowEnd,   zone);

        // Cross-midnight windows not supported; both must fall on the same calendar date.
        if (!localFrom.toLocalDate().equals(localTo.toLocalDate())) {
            return false;
        }

        LocalDate date    = localFrom.toLocalDate();
        int       isoDow  = date.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        LocalTime timeFrom = localFrom.toLocalTime();
        LocalTime timeTo   = localTo.toLocalTime();

        for (TechnicianCandidate.AvailabilityWindow w : windows) {
            if (w.dayOfWeek() != isoDow)                                     continue;
            if (w.effectiveFrom().isAfter(date))                             continue;
            if (w.effectiveTo() != null && w.effectiveTo().isBefore(date)) continue;
            if (w.startTime().isAfter(timeFrom))                             continue;
            if (w.endTime().isBefore(timeTo))                                continue;
            return true;
        }
        return false;
    }
}
