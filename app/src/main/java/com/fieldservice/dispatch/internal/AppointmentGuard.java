package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.ReassignmentService.AppointmentBreachException;
import com.fieldservice.domain.workorder.WorkOrder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Appointment-pinning guard for reassignment (WO-139 AC-6).
 *
 * <h3>Guard rule</h3>
 * If the work order has a confirmed appointment window ({@code appointmentConfirmed = true})
 * AND the window start is in the future (appointment has not passed), the reassignment
 * puts the committed window at risk. The request is refused with 422 unless the dispatcher
 * supplies an explicit {@code appointmentImpactAcknowledgement} reason, which is then
 * persisted on the superseded assignment record.
 *
 * <h3>Past-window exemption</h3>
 * A confirmed appointment window that has already passed does not fire the guard — the
 * commitment has elapsed and no longer constrains the reassignment.
 *
 * <h3>Fail-safe</h3>
 * Unlike the eligibility data guard, a missing appointment field never causes the guard to
 * fail open and allow the assignment. An unconfirmed or null window simply means the guard
 * is not applicable; the guard fires only when {@code appointmentConfirmed = true}.
 */
@Component
public class AppointmentGuard {

    private final Clock clock;

    AppointmentGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluates whether the reassignment requires appointment impact acknowledgement.
     *
     * @param workOrder                       the work order being reassigned
     * @param appointmentImpactAcknowledgement the dispatcher-supplied acknowledgement reason
     *                                         (may be null when not provided)
     * @throws AppointmentBreachException if the work order has a future confirmed appointment
     *                                    and no acknowledgement was supplied
     */
    public void evaluate(WorkOrder workOrder, String appointmentImpactAcknowledgement) {
        if (!workOrder.isAppointmentConfirmed()) {
            return;
        }

        Instant windowStart = workOrder.getScheduledWindowStart();
        if (windowStart == null || !windowStart.isAfter(clock.instant())) {
            // Window is in the past or not set — guard does not apply
            return;
        }

        if (appointmentImpactAcknowledgement == null || appointmentImpactAcknowledgement.isBlank()) {
            throw new AppointmentBreachException(
                    "This work order has a confirmed appointment window starting at " + windowStart
                    + ". Provide appointmentImpactAcknowledgement to confirm you understand the customer impact.");
        }
    }
}
