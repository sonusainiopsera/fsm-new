package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.AssignmentService.CertificationGuardException;
import com.fieldservice.dispatch.api.EligibilityDataException;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.EligibilityResult;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Hard guard re-evaluating certification, active status and availability for a single technician
 * at assignment time (WO-138 AC-5).
 *
 * <p>The guard NEVER fails open: if eligibility data cannot be loaded,
 * {@link EligibilityDataException} propagates and the assignment is refused (503).
 * This satisfies the constraint: "assigning a technician who lacks a current required
 * certification is refused with HTTP 422 regardless of dispatcher intent or override text."
 */
@Component
public class AssignmentGuard {

    private final EligibilityService eligibilityService;

    public AssignmentGuard(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    /**
     * Evaluates the chosen technician for the given work order requirements.
     *
     * @param technicianId  the technician to evaluate
     * @param requirements  work order certification and scheduling constraints
     * @throws CertificationGuardException if the technician is ineligible (422)
     * @throws EligibilityDataException    if eligibility data cannot be loaded (503)
     */
    public void evaluate(UUID technicianId, WorkOrderRequirements requirements) {
        EligibilityResult result = eligibilityService.evaluate(requirements);

        boolean eligible = result.eligibleTechnicianIds().contains(technicianId);
        if (eligible) {
            return;
        }

        String code = result.excluded().stream()
                .filter(e -> technicianId.equals(e.technicianId()))
                .map(e -> mapReasonToCode(e.reason()))
                .findFirst()
                .orElse("TECHNICIAN_INELIGIBLE");

        throw new CertificationGuardException(code, guardMessage(code));
    }

    private static String mapReasonToCode(ExclusionReason reason) {
        return switch (reason) {
            case INACTIVE_TECHNICIAN       -> "TECHNICIAN_INACTIVE";
            case CERTIFICATION_MISSING     -> "CERTIFICATION_MISSING";
            case CERTIFICATION_EXPIRED     -> "CERTIFICATION_EXPIRED";
            case UNAVAILABLE_IN_WINDOW     -> "TECHNICIAN_UNAVAILABLE";
            case OUT_OF_REACH              -> "TECHNICIAN_OUT_OF_REACH";
            case CONFLICTING_APPOINTMENT   -> "CONFLICTING_APPOINTMENT";
        };
    }

    private static String guardMessage(String code) {
        return switch (code) {
            case "TECHNICIAN_INACTIVE"    -> "The technician is not active and cannot be assigned.";
            case "CERTIFICATION_MISSING"  -> "The technician does not hold a required certification.";
            case "CERTIFICATION_EXPIRED"  -> "The technician's required certification has expired.";
            case "TECHNICIAN_UNAVAILABLE" -> "The technician is unavailable during the required service window.";
            case "TECHNICIAN_OUT_OF_REACH"    -> "The technician is outside the service area reach radius.";
            case "CONFLICTING_APPOINTMENT"    -> "The technician is already committed to an overlapping confirmed appointment.";
            default                           -> "The technician is not eligible for this work order.";
        };
    }
}
