package com.fieldservice.dispatch;

import com.fieldservice.dispatch.internal.AppointmentGuard;
import com.fieldservice.dispatch.internal.AssignmentValidationException;
import com.fieldservice.dispatch.internal.ReassignmentNotPermittedException;
import com.fieldservice.dispatch.internal.ReassignmentReason;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for reassignment domain logic — no Spring context required.
 *
 * <p>Covers:
 * <ul>
 *   <li>State eligibility matrix</li>
 *   <li>AppointmentGuard: confirmed future, confirmed past, unconfirmed</li>
 *   <li>Reason-enum validation</li>
 * </ul>
 */
@Tag("unit")
class ReassignmentServiceUnitTest {

    // ── State eligibility matrix ───────────────────────────────────────────────

    @ParameterizedTest(name = "{0} is permitted for reassignment")
    @EnumSource(value = WorkOrderStatus.class, names = {"ASSIGNED", "EN_ROUTE", "ON_HOLD", "IN_PROGRESS"})
    @DisplayName("States ASSIGNED/EN_ROUTE/ON_HOLD/IN_PROGRESS are permitted")
    void stateEligibility_permittedStates_notInSet(WorkOrderStatus state) {
        assertThat(com.fieldservice.dispatch.internal.ReassignmentService.PERMITTED_STATES)
                .contains(state);
    }

    @ParameterizedTest(name = "{0} is NOT permitted for reassignment")
    @EnumSource(value = WorkOrderStatus.class, names = {"NEW", "COMPLETED", "CLOSED", "CANCELLED"})
    @DisplayName("States NEW/COMPLETED/CLOSED/CANCELLED are not permitted")
    void stateEligibility_forbiddenStates_notInSet(WorkOrderStatus state) {
        assertThat(com.fieldservice.dispatch.internal.ReassignmentService.PERMITTED_STATES)
                .doesNotContain(state);
    }

    @Test
    @DisplayName("ReassignmentNotPermittedException names the current state")
    void stateEligibilityException_namesCurrentState() {
        var ex = new ReassignmentNotPermittedException(WorkOrderStatus.CANCELLED);
        assertThat(ex.getCurrentState()).isEqualTo(WorkOrderStatus.CANCELLED);
        assertThat(ex.getMessage()).contains("CANCELLED");
    }

    // ── AppointmentGuard ──────────────────────────────────────────────────────

    private WorkOrder mockWorkOrder(boolean confirmed, Instant windowEnd) {
        WorkOrder wo = mock(WorkOrder.class);
        when(wo.isAppointmentConfirmed()).thenReturn(confirmed);
        when(wo.getAppointmentWindowEnd()).thenReturn(windowEnd);
        when(wo.getId()).thenReturn(UUID.randomUUID());
        return wo;
    }

    @Test
    @DisplayName("AppointmentGuard: confirmed future window without ack throws BusinessGuardException")
    void appointmentGuard_confirmedFuture_noAck_throws() {
        AppointmentGuard guard = new AppointmentGuard();
        WorkOrder wo = mockWorkOrder(true, Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> guard.guard(wo, null))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("confirmed customer appointment");
    }

    @Test
    @DisplayName("AppointmentGuard: confirmed future window with ack passes")
    void appointmentGuard_confirmedFuture_withAck_passes() {
        AppointmentGuard guard = new AppointmentGuard();
        WorkOrder wo = mockWorkOrder(true, Instant.now().plusSeconds(3600));

        assertThatCode(() -> guard.guard(wo, "Customer notified and accepted"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AppointmentGuard: confirmed past window does not require ack")
    void appointmentGuard_confirmedPast_noAckRequired() {
        AppointmentGuard guard = new AppointmentGuard();
        WorkOrder wo = mockWorkOrder(true, Instant.now().minusSeconds(3600));

        assertThatCode(() -> guard.guard(wo, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AppointmentGuard: unconfirmed future window does not require ack")
    void appointmentGuard_unconfirmed_noAckRequired() {
        AppointmentGuard guard = new AppointmentGuard();
        WorkOrder wo = mockWorkOrder(false, Instant.now().plusSeconds(3600));

        assertThatCode(() -> guard.guard(wo, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AppointmentGuard: null window end does not require ack")
    void appointmentGuard_nullWindowEnd_noAckRequired() {
        AppointmentGuard guard = new AppointmentGuard();
        WorkOrder wo = mockWorkOrder(true, null);

        assertThatCode(() -> guard.guard(wo, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AppointmentGuard: blank ack string treated as absent — throws")
    void appointmentGuard_confirmedFuture_blankAck_throws() {
        AppointmentGuard guard = new AppointmentGuard();
        WorkOrder wo = mockWorkOrder(true, Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> guard.guard(wo, "   "))
                .isInstanceOf(BusinessGuardException.class);
    }

    // ── Reason-enum validation ────────────────────────────────────────────────

    @Test
    @DisplayName("All declared ReassignmentReason values are valid enum constants")
    void reassignmentReason_allValuesValid() {
        for (ReassignmentReason reason : ReassignmentReason.values()) {
            assertThat(ReassignmentReason.valueOf(reason.name())).isEqualTo(reason);
        }
    }

    @Test
    @DisplayName("ReassignmentReason enum has exactly 7 values matching CHECK constraint")
    void reassignmentReason_sevenValues() {
        assertThat(ReassignmentReason.values()).hasSize(7);
    }
}
