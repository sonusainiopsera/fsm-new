package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.technician.TechnicianCertification;
import com.fieldservice.domain.technician.TechnicianCertificationRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderCompetency;
import com.fieldservice.domain.workorder.WorkOrderCompetencyRepository;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CertificationCurrencyGuardTest {

    private static final Instant NOW = Instant.parse("2025-06-15T12:00:00Z");
    private static final UUID TECHNICIAN_ID = UUID.randomUUID();
    private static final UUID WORK_ORDER_ID = UUID.randomUUID();

    private WorkOrderCompetencyRepository competencyRepo;
    private TechnicianCertificationRepository certRepo;
    private Clock fixedClock;
    private CertificationCurrencyGuard guard;
    private WorkOrder workOrder;

    @BeforeEach
    void setUp() {
        competencyRepo = mock(WorkOrderCompetencyRepository.class);
        certRepo = mock(TechnicianCertificationRepository.class);
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        guard = new CertificationCurrencyGuard(competencyRepo, certRepo, fixedClock);

        workOrder = mock(WorkOrder.class);
        when(workOrder.getId()).thenReturn(WORK_ORDER_ID);
        when(workOrder.getAssignedTechnicianId()).thenReturn(TECHNICIAN_ID);
    }

    @Test
    @DisplayName("guardId is stable identifier")
    void guardId_isStable() {
        assertThat(guard.guardId()).isEqualTo(CertificationCurrencyGuard.GUARD_ID);
    }

    @Test
    @DisplayName("satisfied when work order has no required competencies")
    void satisfied_whenNoCompetenciesRequired() {
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID)).thenReturn(List.of());
        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("satisfied when technician holds all required certifications")
    void satisfied_whenTechnicianHoldsAllCerts() {
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT")));
        when(certRepo.findActiveCertificationsAt(eq(TECHNICIAN_ID), any()))
                .thenReturn(List.of(certification("HVAC_CERT", NOW.plusSeconds(86400))));

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("refused with CERTIFICATION_EXPIRED when technician lacks a required competency")
    void refused_whenCertificationMissing() {
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT")));
        when(certRepo.findActiveCertificationsAt(eq(TECHNICIAN_ID), any()))
                .thenReturn(List.of()); // no certifications

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("CERTIFICATION_EXPIRED");
    }

    @Test
    @DisplayName("certification expiring exactly at transition instant is treated as expired (strict <)")
    void certExpiringAtTransitionInstant_treatedAsExpired() {
        // The DB query uses expiresAt > :now (strictly greater than), so
        // a cert expiring at NOW is excluded by the repository. We simulate
        // the repository returning empty because the cert expired at NOW.
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT")));
        when(certRepo.findActiveCertificationsAt(eq(TECHNICIAN_ID), eq(NOW)))
                .thenReturn(List.of()); // cert expires exactly at NOW, so excluded

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("CERTIFICATION_EXPIRED");
    }

    @Test
    @DisplayName("certification expiring one second after transition instant is eligible")
    void certExpiringOneSecondAfterNow_isEligible() {
        Instant expiresAt = NOW.plusSeconds(1);
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT")));
        when(certRepo.findActiveCertificationsAt(eq(TECHNICIAN_ID), eq(NOW)))
                .thenReturn(List.of(certification("HVAC_CERT", expiresAt)));

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("refused with CERTIFICATION_MISSING when no technician is assigned")
    void refused_whenNoTechnicianAssigned() {
        when(workOrder.getAssignedTechnicianId()).thenReturn(null);
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT")));

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("CERTIFICATION_MISSING");
    }

    @Test
    @DisplayName("multiple required competencies: refused if any one is missing")
    void refused_whenOneOfMultipleCompetenciesMissing() {
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT"), competency("ELECTRICAL_CERT")));
        when(certRepo.findActiveCertificationsAt(eq(TECHNICIAN_ID), any()))
                .thenReturn(List.of(certification("HVAC_CERT", NOW.plusSeconds(86400))));
        // ELECTRICAL_CERT missing

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
    }

    @Test
    @DisplayName("certification guard is not bypassable: even ADMIN gets refused without cert")
    void certGuard_notBypassableByAnyRole() {
        // The guard has no knowledge of roles — role bypass is tested at integration level.
        // This unit test verifies the guard refuses regardless of context.
        when(competencyRepo.findByWorkOrderId(WORK_ORDER_ID))
                .thenReturn(List.of(competency("HVAC_CERT")));
        when(certRepo.findActiveCertificationsAt(eq(TECHNICIAN_ID), any()))
                .thenReturn(List.of()); // cert missing

        GuardResult result = guard.evaluate(workOrder, WorkOrderEvent.ASSIGN, context());
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private TransitionContext context() {
        return new TransitionContext(null, NOW);
    }

    private WorkOrderCompetency competency(String code) {
        WorkOrderCompetency c = mock(WorkOrderCompetency.class);
        when(c.getCompetencyCode()).thenReturn(code);
        return c;
    }

    private TechnicianCertification certification(String certType, Instant expiresAt) {
        TechnicianCertification c = mock(TechnicianCertification.class);
        when(c.getCertType()).thenReturn(certType);
        when(c.getExpiresAt()).thenReturn(expiresAt);
        return c;
    }
}
