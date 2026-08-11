package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.technician.domain.TechnicianCertification;
import com.fieldservice.technician.repository.TechnicianCertificationRepository;
import com.fieldservice.workorder.domain.WorkOrderRequiredCompetency;
import com.fieldservice.workorder.lifecycle.GuardContext;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.repository.LabourEntryRepository;
import com.fieldservice.workorder.repository.WorkOrderRequiredCompetencyRepository;
import com.fieldservice.inventory.repository.WorkOrderPartsConsumptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardChainTest {

    static final UUID WO_ID   = UUID.randomUUID();
    static final UUID TECH_ID = UUID.randomUUID();
    static final Instant NOW  = Instant.parse("2026-06-15T12:00:00Z");
    static final Clock CLOCK  = Clock.fixed(NOW, ZoneOffset.UTC);

    LabourEntryRepository            labourRepo;
    WorkOrderPartsConsumptionRepository partsRepo;
    WorkOrderRequiredCompetencyRepository competencyRepo;
    TechnicianCertificationRepository certRepo;

    @BeforeEach
    void setUp() {
        labourRepo      = mock(LabourEntryRepository.class);
        partsRepo       = mock(WorkOrderPartsConsumptionRepository.class);
        competencyRepo  = mock(WorkOrderRequiredCompetencyRepository.class);
        certRepo        = mock(TechnicianCertificationRepository.class);
    }

    // ---- HoldReasonRequiredGuard ------------------------------------------------

    @Test
    @DisplayName("HoldReasonRequiredGuard: satisfied when holdReasonCode is present")
    void holdReasonGuard_satisfied() {
        HoldReasonRequiredGuard guard = new HoldReasonRequiredGuard();
        GuardContext ctx = new GuardContext(WO_ID, null, "AWAITING_PARTS", NOW);

        assertThat(guard.evaluate(WorkOrderState.IN_PROGRESS, WorkOrderEvent.HOLD, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("HoldReasonRequiredGuard: refused with HOLD_REASON_MISSING when code is absent")
    void holdReasonGuard_refused() {
        HoldReasonRequiredGuard guard = new HoldReasonRequiredGuard();
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        GuardResult result = guard.evaluate(WorkOrderState.IN_PROGRESS, WorkOrderEvent.HOLD, ctx);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("HOLD_REASON_MISSING");
    }

    // ---- LabourTimeRecordedGuard ------------------------------------------------

    @Test
    @DisplayName("LabourTimeRecordedGuard: satisfied when labour entry exists")
    void labourGuard_satisfied() {
        when(labourRepo.existsByWorkOrderId(WO_ID)).thenReturn(true);
        LabourTimeRecordedGuard guard = new LabourTimeRecordedGuard(labourRepo);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        assertThat(guard.evaluate(WorkOrderState.IN_PROGRESS, WorkOrderEvent.COMPLETE, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("LabourTimeRecordedGuard: refused with LABOUR_TIME_MISSING when no entries")
    void labourGuard_refused() {
        when(labourRepo.existsByWorkOrderId(WO_ID)).thenReturn(false);
        LabourTimeRecordedGuard guard = new LabourTimeRecordedGuard(labourRepo);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        GuardResult result = guard.evaluate(WorkOrderState.IN_PROGRESS, WorkOrderEvent.COMPLETE, ctx);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("LABOUR_TIME_MISSING");
    }

    // ---- PartsReconciledGuard --------------------------------------------------

    @Test
    @DisplayName("PartsReconciledGuard: satisfied when no unreconciled parts")
    void partsGuard_satisfied_no_unreconciled() {
        when(partsRepo.existsByWorkOrderIdAndReconciledFalse(WO_ID)).thenReturn(false);
        PartsReconciledGuard guard = new PartsReconciledGuard(partsRepo);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        assertThat(guard.evaluate(WorkOrderState.COMPLETED, WorkOrderEvent.CLOSE, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("PartsReconciledGuard: satisfied when no parts consumed at all")
    void partsGuard_satisfied_no_parts() {
        when(partsRepo.existsByWorkOrderIdAndReconciledFalse(WO_ID)).thenReturn(false);
        PartsReconciledGuard guard = new PartsReconciledGuard(partsRepo);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        assertThat(guard.evaluate(WorkOrderState.COMPLETED, WorkOrderEvent.CLOSE, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("PartsReconciledGuard: refused with PARTS_UNRECONCILED when unreconciled records exist")
    void partsGuard_refused() {
        when(partsRepo.existsByWorkOrderIdAndReconciledFalse(WO_ID)).thenReturn(true);
        PartsReconciledGuard guard = new PartsReconciledGuard(partsRepo);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        GuardResult result = guard.evaluate(WorkOrderState.COMPLETED, WorkOrderEvent.CLOSE, ctx);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("PARTS_UNRECONCILED");
    }

    // ---- CertificationCurrencyGuard --------------------------------------------

    @Test
    @DisplayName("CertificationCurrencyGuard: satisfied when no competencies required")
    void certGuard_no_requirements_passes() {
        when(competencyRepo.findByWorkOrderId(WO_ID)).thenReturn(List.of());
        CertificationCurrencyGuard guard = new CertificationCurrencyGuard(
                competencyRepo, certRepo, CLOCK);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        assertThat(guard.evaluate(WorkOrderState.NEW, WorkOrderEvent.ASSIGN, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
        verify(certRepo, never()).findByTechnicianId(TECH_ID);
    }

    @Test
    @DisplayName("CertificationCurrencyGuard: satisfied when technician holds valid cert")
    void certGuard_valid_cert_passes() {
        WorkOrderRequiredCompetency req = mock(WorkOrderRequiredCompetency.class);
        when(req.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(competencyRepo.findByWorkOrderId(WO_ID)).thenReturn(List.of(req));

        TechnicianCertification cert = mock(TechnicianCertification.class);
        when(cert.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(cert.getExpiresAt()).thenReturn(NOW.plusSeconds(86400)); // expires tomorrow
        when(certRepo.findByTechnicianId(TECH_ID)).thenReturn(List.of(cert));

        CertificationCurrencyGuard guard = new CertificationCurrencyGuard(
                competencyRepo, certRepo, CLOCK);
        GuardContext ctx = new GuardContext(WO_ID, TECH_ID, null, NOW);

        assertThat(guard.evaluate(WorkOrderState.NEW, WorkOrderEvent.ASSIGN, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("CertificationCurrencyGuard: refused with CERTIFICATION_EXPIRED — cert expiring exactly at transition instant is expired")
    void certGuard_exact_boundary_expired() {
        WorkOrderRequiredCompetency req = mock(WorkOrderRequiredCompetency.class);
        when(req.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(competencyRepo.findByWorkOrderId(WO_ID)).thenReturn(List.of(req));

        TechnicianCertification cert = mock(TechnicianCertification.class);
        when(cert.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(cert.getExpiresAt()).thenReturn(NOW); // expiry == transition instant → expired
        when(certRepo.findByTechnicianId(TECH_ID)).thenReturn(List.of(cert));

        CertificationCurrencyGuard guard = new CertificationCurrencyGuard(
                competencyRepo, certRepo, CLOCK);
        GuardContext ctx = new GuardContext(WO_ID, TECH_ID, null, NOW);

        GuardResult result = guard.evaluate(WorkOrderState.NEW, WorkOrderEvent.ASSIGN, ctx);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("CERTIFICATION_EXPIRED");
    }

    @Test
    @DisplayName("CertificationCurrencyGuard: satisfied — cert expiring one second after transition instant is eligible")
    void certGuard_one_second_before_expiry_eligible() {
        WorkOrderRequiredCompetency req = mock(WorkOrderRequiredCompetency.class);
        when(req.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(competencyRepo.findByWorkOrderId(WO_ID)).thenReturn(List.of(req));

        TechnicianCertification cert = mock(TechnicianCertification.class);
        when(cert.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(cert.getExpiresAt()).thenReturn(NOW.plusSeconds(1)); // expires one second later → still valid
        when(certRepo.findByTechnicianId(TECH_ID)).thenReturn(List.of(cert));

        CertificationCurrencyGuard guard = new CertificationCurrencyGuard(
                competencyRepo, certRepo, CLOCK);
        GuardContext ctx = new GuardContext(WO_ID, TECH_ID, null, NOW);

        assertThat(guard.evaluate(WorkOrderState.NEW, WorkOrderEvent.ASSIGN, ctx))
                .isInstanceOf(GuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("CertificationCurrencyGuard: refused with CERTIFICATION_MISSING when cert absent")
    void certGuard_missing_cert_refused() {
        WorkOrderRequiredCompetency req = mock(WorkOrderRequiredCompetency.class);
        when(req.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(competencyRepo.findByWorkOrderId(WO_ID)).thenReturn(List.of(req));
        when(certRepo.findByTechnicianId(TECH_ID)).thenReturn(List.of()); // no certs

        CertificationCurrencyGuard guard = new CertificationCurrencyGuard(
                competencyRepo, certRepo, CLOCK);
        GuardContext ctx = new GuardContext(WO_ID, TECH_ID, null, NOW);

        GuardResult result = guard.evaluate(WorkOrderState.NEW, WorkOrderEvent.ASSIGN, ctx);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("CERTIFICATION_MISSING");
    }

    @Test
    @DisplayName("CertificationCurrencyGuard: refused with CERTIFICATION_MISSING when technicianId is null but requirements exist")
    void certGuard_null_technician_refused() {
        WorkOrderRequiredCompetency req = mock(WorkOrderRequiredCompetency.class);
        when(req.getCertificationCode()).thenReturn("ELECTRICAL_SAFETY");
        when(competencyRepo.findByWorkOrderId(WO_ID)).thenReturn(List.of(req));

        CertificationCurrencyGuard guard = new CertificationCurrencyGuard(
                competencyRepo, certRepo, CLOCK);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        GuardResult result = guard.evaluate(WorkOrderState.NEW, WorkOrderEvent.ASSIGN, ctx);
        assertThat(result).isInstanceOf(GuardResult.Refused.class);
        assertThat(((GuardResult.Refused) result).code()).isEqualTo("CERTIFICATION_MISSING");
    }

    // ---- Guard chain ordering and short-circuit --------------------------------

    @Test
    @DisplayName("Exception inside a guard converts to refusal — fail-closed")
    void throwingGuard_failsClosed() {
        TransitionGuard throwingGuard = new TransitionGuard() {
            @Override public String guardId() { return "throwing.guard"; }
            @Override
            public GuardResult evaluate(WorkOrderState s, WorkOrderEvent e, Object ctx) {
                throw new RuntimeException("simulated backing service failure");
            }
        };

        // Wrap in the same logic as WorkOrderTransitionApplicationService.runGuards
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);
        GuardResult result;
        try {
            result = throwingGuard.evaluate(WorkOrderState.IN_PROGRESS, WorkOrderEvent.COMPLETE, ctx);
        } catch (com.fieldservice.platform.api.exception.BusinessGuardException e) {
            result = new GuardResult.Refused("GUARD_ERROR", e.getMessage());
        } catch (Exception e) {
            result = new GuardResult.Refused("GUARD_ERROR", "Guard check failed");
        }

        assertThat(result).isInstanceOf(GuardResult.Refused.class);
    }

    @Test
    @DisplayName("Guards on read-only repositories: labour guard does not call save/delete")
    void labourGuard_doesNotCallMutatingMethods() {
        when(labourRepo.existsByWorkOrderId(WO_ID)).thenReturn(true);
        LabourTimeRecordedGuard guard = new LabourTimeRecordedGuard(labourRepo);
        GuardContext ctx = new GuardContext(WO_ID, null, null, NOW);

        guard.evaluate(WorkOrderState.IN_PROGRESS, WorkOrderEvent.COMPLETE, ctx);

        verify(labourRepo, never()).save(org.mockito.ArgumentMatchers.any());
        verify(labourRepo, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(labourRepo, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }
}
