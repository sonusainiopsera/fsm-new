package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.dispatch.internal.Haversine;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, deterministic eligibility filter.
 *
 * <p>Framework-free: no Spring annotations, no JPA, no HTTP dependencies.
 * Constructed with an injected {@link Clock} for testability of expiry boundaries.
 *
 * <p>Rules are evaluated in declaration order; the <em>first</em> failing rule
 * becomes the primary exclusion reason when a technician violates multiple rules.
 *
 * <ol>
 *   <li>INACTIVE_TECHNICIAN — {@code active == false}</li>
 *   <li>CERTIFICATION_MISSING — required code absent from candidate's cert set</li>
 *   <li>CERTIFICATION_EXPIRED — required code present but all instances expired</li>
 *   <li>UNAVAILABLE_IN_WINDOW — absence overlaps or no shift window covers the interval</li>
 *   <li>OUT_OF_REACH — Haversine distance exceeds {@code maxReachKm}</li>
 * </ol>
 *
 * <p>Geographic filtering is skipped when either the site location or the candidate's
 * home-base coordinates are unknown; such candidates contribute to
 * {@link EligibilityResult#reachUnknownCount()}.
 */
class EligibilityFilter {

    private final Clock clock;

    EligibilityFilter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Applies all eligibility rules to every candidate.
     *
     * @param candidates     pool to evaluate; must not be null
     * @param requirements   work order constraints; must not be null
     * @return structured result with eligible identifiers and exclusion records
     */
    EligibilityResult filter(List<TechnicianCandidate> candidates,
                              WorkOrderRequirements requirements) {

        List<UUID> eligible  = new ArrayList<>();
        List<ExcludedCandidate> excluded = new ArrayList<>();
        int reachUnknownCount = 0;

        // Evaluation date = LocalDate of window start in UTC for certification boundary check
        LocalDate evalDate = requirements.serviceWindowStart()
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate();

        for (TechnicianCandidate c : candidates) {
            ExclusionReason reason = evaluate(c, requirements, evalDate);

            if (reason == null) {
                // All explicit rules passed; now geo check
                Boolean reachResult = checkReach(c, requirements);
                if (reachResult == null) {
                    // coordinates unknown — skip geo, note for caller
                    eligible.add(c.id());
                    reachUnknownCount++;
                } else if (reachResult) {
                    eligible.add(c.id());
                } else {
                    excluded.add(new ExcludedCandidate(c.id(), ExclusionReason.OUT_OF_REACH));
                }
            } else {
                excluded.add(new ExcludedCandidate(c.id(), reason));
            }
        }

        return new EligibilityResult(eligible, excluded, reachUnknownCount);
    }

    // ── Private rule evaluation ───────────────────────────────────────────────

    /**
     * Evaluates all non-geo rules in declaration order.
     *
     * @return the first failing reason, or {@code null} if all non-geo rules pass
     */
    private ExclusionReason evaluate(TechnicianCandidate c,
                                      WorkOrderRequirements req,
                                      LocalDate evalDate) {
        // Rule 1: inactive flag
        if (!c.active()) {
            return ExclusionReason.INACTIVE_TECHNICIAN;
        }

        // Rules 2 & 3: certification checks (only when there are requirements)
        for (String requiredCode : req.requiredCertificationCodes()) {
            ExclusionReason certReason = checkCertification(c.certifications(), requiredCode, evalDate);
            if (certReason != null) {
                return certReason;
            }
        }

        // Rule 4: availability
        if (!isAvailable(c, req.serviceWindowStart(), req.serviceWindowEnd())) {
            return ExclusionReason.UNAVAILABLE_IN_WINDOW;
        }

        return null;
    }

    /**
     * Checks whether the candidate holds a current certification for the given code.
     *
     * @return CERTIFICATION_MISSING, CERTIFICATION_EXPIRED, or null (requirement satisfied)
     */
    private ExclusionReason checkCertification(List<TechnicianCandidate.CertSnap> certs,
                                                String requiredCode,
                                                LocalDate evalDate) {
        boolean anyRecord = false;
        for (TechnicianCandidate.CertSnap snap : certs) {
            if (!requiredCode.equals(snap.typeCode())) continue;
            anyRecord = true;
            if (isCurrent(snap, evalDate)) {
                return null; // found a current one — satisfied
            }
        }
        if (!anyRecord) return ExclusionReason.CERTIFICATION_MISSING;
        // Records exist but none are current
        return ExclusionReason.CERTIFICATION_EXPIRED;
    }

    /**
     * Currency predicate: {@code expires_on == null} (perpetual) OR {@code expires_on >= evalDate}.
     *
     * <p>Boundary: certified until the 30th means eligible on the 30th (inclusive)
     * and ineligible from the 1st (the next day). No grace period.
     */
    private boolean isCurrent(TechnicianCandidate.CertSnap snap, LocalDate evalDate) {
        return snap.expiresOn() == null || !snap.expiresOn().isBefore(evalDate);
    }

    /**
     * Returns {@code true} when:
     * <ul>
     *   <li>No absence overlaps {@code [from, to)}, AND</li>
     *   <li>At least one effective working window on the start day covers the required time slice.</li>
     * </ul>
     */
    private boolean isAvailable(TechnicianCandidate c, Instant from, Instant to) {
        // Check absence overlap
        for (TechnicianCandidate.AbsenceSnap abs : c.absences()) {
            if (abs.startsAt().isBefore(to) && abs.endsAt().isAfter(from)) {
                return false;
            }
        }

        if (c.availabilityWindows().isEmpty()) {
            return false;
        }

        // Determine day and time slice in the technician's timezone
        ZoneId tz;
        try {
            tz = ZoneId.of(c.timezone());
        } catch (Exception e) {
            tz = java.time.ZoneOffset.UTC;
        }

        ZonedDateTime fromZdt = from.atZone(tz);
        LocalDate startDate   = fromZdt.toLocalDate();
        DayOfWeek dow         = startDate.getDayOfWeek();
        LocalTime requiredStart = fromZdt.toLocalTime();
        LocalTime requiredEnd   = to.atZone(tz).toLocalTime();
        if (requiredEnd.equals(LocalTime.MIDNIGHT)) {
            // end of day — treat as end of business
            requiredEnd = LocalTime.MAX;
        }

        for (TechnicianCandidate.WindowSnap win : c.availabilityWindows()) {
            if (win.dayOfWeek() == dow
                    && isWindowEffective(win, startDate)
                    && !win.startTime().isAfter(requiredStart)
                    && !win.endTime().isBefore(requiredEnd)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWindowEffective(TechnicianCandidate.WindowSnap win, LocalDate date) {
        return !date.isBefore(win.effectiveFrom())
                && (win.effectiveTo() == null || !date.isAfter(win.effectiveTo()));
    }

    /**
     * Evaluates geographic reach.
     *
     * @return {@code true} = within reach, {@code false} = out of reach,
     *         {@code null} = coordinates unknown (geo check skipped)
     */
    private Boolean checkReach(TechnicianCandidate c, WorkOrderRequirements req) {
        if (req.siteLatitude() == null || req.siteLongitude() == null) {
            return null; // site has no coords
        }
        if (c.homeLatitude() == null || c.homeLongitude() == null) {
            return null; // technician home base has no coords
        }
        double distKm = Haversine.distanceKm(
                c.homeLatitude(), c.homeLongitude(),
                req.siteLatitude(), req.siteLongitude());
        return distKm <= req.maxReachKm();
    }
}
