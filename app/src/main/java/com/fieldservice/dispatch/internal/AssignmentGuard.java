package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ProviderDegradedException;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.repository.WorkOrderRequiredCompetencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Hard certification guard evaluated at assignment time.
 *
 * <p>This guard is fail-closed: if eligibility data cannot be loaded, the assignment is
 * refused with a 503 response rather than allowed. It can never be bypassed by any
 * request field including overrideReason.
 *
 * <p>Uses the same DEFAULT_REACH_KM and today-UTC service window as the recommendation
 * orchestrator so the guard evaluates under the same conditions that produced the snapshot.
 */
@Component
public class AssignmentGuard {

    private static final Logger log = LoggerFactory.getLogger(AssignmentGuard.class);

    static final double DEFAULT_REACH_KM = 200.0;

    private final EligibilityService                    eligibilityService;
    private final WorkOrderRequiredCompetencyRepository competencyRepository;

    public AssignmentGuard(EligibilityService eligibilityService,
                           WorkOrderRequiredCompetencyRepository competencyRepository) {
        this.eligibilityService    = eligibilityService;
        this.competencyRepository  = competencyRepository;
    }

    /**
     * Evaluates whether {@code technicianId} passes all eligibility rules for the
     * given work order at the current instant.
     *
     * @param workOrder    the work order being assigned
     * @param technicianId the chosen technician
     * @throws BusinessGuardException      (HTTP 422) when the technician is ineligible
     * @throws ProviderDegradedException   (HTTP 503) when eligibility data is unavailable
     */
    public void guard(WorkOrder workOrder, UUID technicianId) {
        List<String> requiredCerts = competencyRepository.findByWorkOrderId(workOrder.getId())
                .stream()
                .map(c -> c.getCertificationCode())
                .toList();

        Double siteLat = null;
        Double siteLon = null;
        try {
            if (workOrder.getSite() != null) {
                siteLat = workOrder.getSite().getLatitude() != null
                        ? workOrder.getSite().getLatitude().doubleValue() : null;
                siteLon = workOrder.getSite().getLongitude() != null
                        ? workOrder.getSite().getLongitude().doubleValue() : null;
            }
        } catch (Exception e) {
            log.warn("assignment_guard_site_load_failed workOrderId={} reason={}",
                    workOrder.getId(), e.getMessage());
        }

        Instant windowStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd   = windowStart.plusSeconds(28_800); // +8 h

        WorkOrderRequirements requirements = new WorkOrderRequirements(
                requiredCerts, windowStart, windowEnd, siteLat, siteLon, DEFAULT_REACH_KM);

        EligibilityResult result;
        try {
            result = eligibilityService.evaluate(requirements);
        } catch (Exception e) {
            log.warn("assignment_guard_eligibility_unavailable workOrderId={} technicianId={} reason={}",
                    workOrder.getId(), technicianId, e.getMessage());
            throw new ProviderDegradedException("eligibility");
        }

        if (result.eligible().contains(technicianId)) {
            return;
        }

        ExclusionReason reason = result.excluded().stream()
                .filter(ex -> technicianId.equals(ex.technicianId()))
                .map(ExcludedCandidate::reason)
                .findFirst()
                .orElse(null);

        String code = reason != null ? reason.name() : "TECHNICIAN_INELIGIBLE";
        String message = buildMessage(reason, technicianId);

        log.info("assignment_guard_refused workOrderId={} technicianId={} reason={}",
                workOrder.getId(), technicianId, code);
        throw new BusinessGuardException(code, message);
    }

    private static String buildMessage(ExclusionReason reason, UUID technicianId) {
        if (reason == null) {
            return "Technician " + technicianId + " is not eligible for assignment.";
        }
        return switch (reason) {
            case INACTIVE_TECHNICIAN    -> "Technician is inactive and cannot be assigned.";
            case CERTIFICATION_MISSING  -> "Technician does not hold the required certification.";
            case CERTIFICATION_EXPIRED  -> "Technician's required certification has expired.";
            case UNAVAILABLE_IN_WINDOW  -> "Technician is unavailable during the required service window.";
            case OUT_OF_REACH           -> "Technician's home base is outside the maximum reach radius.";
        };
    }
}
