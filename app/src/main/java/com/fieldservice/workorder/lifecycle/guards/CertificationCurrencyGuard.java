package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.technician.domain.TechnicianCertification;
import com.fieldservice.technician.repository.TechnicianCertificationRepository;
import com.fieldservice.workorder.lifecycle.GuardContext;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.repository.WorkOrderRequiredCompetencyRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class CertificationCurrencyGuard implements TransitionGuard {

    public static final String GUARD_ID = "certification.current";

    private final WorkOrderRequiredCompetencyRepository competencyRepository;
    private final TechnicianCertificationRepository certificationRepository;
    private final Clock clock;

    public CertificationCurrencyGuard(WorkOrderRequiredCompetencyRepository competencyRepository,
                                       TechnicianCertificationRepository certificationRepository,
                                       Clock clock) {
        this.competencyRepository   = competencyRepository;
        this.certificationRepository = certificationRepository;
        this.clock                  = clock;
    }

    @Override
    public String guardId() { return GUARD_ID; }

    @Override
    public GuardResult evaluate(WorkOrderState fromState, WorkOrderEvent event, Object context) {
        GuardContext ctx = (GuardContext) context;

        List<String> requiredCodes = competencyRepository.findByWorkOrderId(ctx.workOrderId())
                .stream()
                .map(c -> c.getCertificationCode())
                .toList();

        if (requiredCodes.isEmpty()) {
            return new GuardResult.Satisfied();
        }

        UUID technicianId = ctx.technicianId();
        if (technicianId == null) {
            return new GuardResult.Refused(
                    "CERTIFICATION_MISSING",
                    "A technician must be specified to verify certification requirements.");
        }

        Instant evalInstant = ctx.transitionInstant();
        List<TechnicianCertification> allCerts = certificationRepository.findByTechnicianId(technicianId);

        Map<String, Boolean> validByCode = allCerts.stream()
                .filter(cert -> cert.getExpiresAt() == null || cert.getExpiresAt().isAfter(evalInstant))
                .collect(Collectors.toMap(
                        TechnicianCertification::getCertificationCode,
                        c -> true,
                        (a, b) -> true));

        for (String code : requiredCodes) {
            if (!validByCode.containsKey(code)) {
                boolean hasExpiredCert = allCerts.stream()
                        .anyMatch(c -> code.equals(c.getCertificationCode()));
                if (hasExpiredCert) {
                    return new GuardResult.Refused(
                            "CERTIFICATION_EXPIRED",
                            "Technician's certification for '" + code + "' has expired and must be renewed before assignment.");
                }
                return new GuardResult.Refused(
                        "CERTIFICATION_MISSING",
                        "Technician does not hold the required certification '" + code + "'.");
            }
        }

        return new GuardResult.Satisfied();
    }
}
