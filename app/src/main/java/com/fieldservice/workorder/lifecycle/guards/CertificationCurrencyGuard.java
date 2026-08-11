package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.technician.TechnicianCertification;
import com.fieldservice.domain.technician.TechnicianCertificationRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderCompetency;
import com.fieldservice.domain.workorder.WorkOrderCompetencyRepository;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Refuses ASSIGN when the target technician lacks any required competency or holds
 * a certification that expires at or before the transition instant (BR-01, BR-02).
 *
 * <p>Expiry comparison is strict: a certification with {@code expiresAt} equal to the
 * transition instant is treated as expired. Expired certifications are absent with no
 * grace period for regulated categories.
 *
 * <p>A work order with no required competency rows passes this guard regardless of the
 * technician's certifications.
 */
@Component
public class CertificationCurrencyGuard implements TransitionGuard {

    public static final String GUARD_ID = "certification-currency";

    private final WorkOrderCompetencyRepository competencyRepository;
    private final TechnicianCertificationRepository certificationRepository;
    private final Clock clock;

    public CertificationCurrencyGuard(
            WorkOrderCompetencyRepository competencyRepository,
            TechnicianCertificationRepository certificationRepository,
            Clock clock) {
        this.competencyRepository = competencyRepository;
        this.certificationRepository = certificationRepository;
        this.clock = clock;
    }

    @Override
    public String guardId() {
        return GUARD_ID;
    }

    @Override
    public GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event, TransitionContext context) {
        UUID technicianId = workOrder.getAssignedTechnicianId();
        if (technicianId == null) {
            return new GuardResult.Refused(
                    "CERTIFICATION_MISSING",
                    "Work order " + workOrder.getId() + " cannot be assigned: no technician is set " +
                    "on the work order. Set the assigned technician before applying ASSIGN.");
        }

        List<WorkOrderCompetency> required = competencyRepository.findByWorkOrderId(workOrder.getId());
        if (required.isEmpty()) {
            return new GuardResult.Satisfied();
        }

        Instant transitionInstant = context.transitionInstant() != null
                ? context.transitionInstant()
                : clock.instant();

        Set<String> heldCertTypes = certificationRepository
                .findActiveCertificationsAt(technicianId, transitionInstant)
                .stream()
                .map(TechnicianCertification::getCertType)
                .collect(Collectors.toSet());

        for (WorkOrderCompetency competency : required) {
            if (!heldCertTypes.contains(competency.getCompetencyCode())) {
                return new GuardResult.Refused(
                        "CERTIFICATION_EXPIRED",
                        "Work order " + workOrder.getId() + " cannot be assigned to technician " +
                        technicianId + ": required competency '" + competency.getCompetencyCode() +
                        "' is missing or expired. Assign a technician with a current certification.");
            }
        }

        return new GuardResult.Satisfied();
    }
}
