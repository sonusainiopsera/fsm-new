package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderCompetency;
import com.fieldservice.domain.workorder.WorkOrderCompetencyRepository;
import com.fieldservice.workforce.api.CertificationGuardPort;
import com.fieldservice.workforce.api.CertificationNotCurrentException;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Refuses ASSIGN when the target technician lacks any required regulated competency (BR-01, BR-02).
 *
 * <p>Currency predicate: {@code expires_on >= atDate} — inclusive, evaluated at query time.
 * No grace period is applied for any certification type. No override parameter exists.
 *
 * <p>Fail-safe: any exception from the certification port propagates — the guard never fails open.
 */
@Component
public class CertificationCurrencyGuard implements TransitionGuard {

    public static final String GUARD_ID = "certification-currency";

    private final WorkOrderCompetencyRepository competencyRepository;
    private final CertificationGuardPort certificationGuardPort;
    private final Clock clock;

    public CertificationCurrencyGuard(
            WorkOrderCompetencyRepository competencyRepository,
            CertificationGuardPort certificationGuardPort,
            Clock clock) {
        this.competencyRepository   = competencyRepository;
        this.certificationGuardPort = certificationGuardPort;
        this.clock                  = clock;
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
                    "Work order " + workOrder.getId() + " cannot be assigned: no technician is set. " +
                    "Set the assigned technician before applying ASSIGN.");
        }

        List<WorkOrderCompetency> required = competencyRepository.findByWorkOrderId(workOrder.getId());
        if (required.isEmpty()) {
            return new GuardResult.Satisfied();
        }

        LocalDate atDate = resolveDate(context);
        Set<String> requiredCodes = required.stream()
                .map(WorkOrderCompetency::getCompetencyCode)
                .collect(Collectors.toSet());

        try {
            certificationGuardPort.assertAssignable(technicianId, requiredCodes, atDate);
            return new GuardResult.Satisfied();
        } catch (CertificationNotCurrentException e) {
            return new GuardResult.Refused(
                    "CERTIFICATION_NOT_CURRENT",
                    "Work order " + workOrder.getId() + " cannot be assigned to technician " +
                    technicianId + ": regulated certification(s) not current: " +
                    e.getMissingTypeCodes());
        }
        // Any other exception propagates — guard never fails open
    }

    private LocalDate resolveDate(TransitionContext context) {
        if (context.transitionInstant() != null) {
            return context.transitionInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.now(clock);
    }
}
