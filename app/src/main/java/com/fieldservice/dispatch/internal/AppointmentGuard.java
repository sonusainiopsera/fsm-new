package com.fieldservice.dispatch.internal;

import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.workorder.domain.WorkOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Guards reassignment against silently breaking a confirmed customer appointment.
 *
 * <p>A confirmed appointment window is a pinned constraint: if the work order has a
 * future confirmed window, the guard refuses the reassignment unless the dispatcher
 * supplies an explicit {@code appointmentImpactAcknowledgement} reason.
 *
 * <p>The guard does not fire when:
 * <ul>
 *   <li>The work order has no appointment window ({@code appointmentWindowEnd} is null).</li>
 *   <li>The appointment window has already passed (end is in the past).</li>
 *   <li>The appointment is not yet confirmed ({@code appointmentConfirmed = false}).</li>
 * </ul>
 */
@Component
public class AppointmentGuard {

    private static final Logger log = LoggerFactory.getLogger(AppointmentGuard.class);

    /**
     * Evaluates whether the reassignment requires explicit appointment acknowledgement.
     *
     * @param workOrder                       the work order being reassigned
     * @param appointmentImpactAcknowledgement dispatcher-supplied reason, or null/blank if not provided
     * @throws BusinessGuardException (HTTP 422) when acknowledgement is required but absent
     */
    public void guard(WorkOrder workOrder, String appointmentImpactAcknowledgement) {
        if (!workOrder.isAppointmentConfirmed()) {
            return;
        }
        Instant windowEnd = workOrder.getAppointmentWindowEnd();
        if (windowEnd == null || !windowEnd.isAfter(Instant.now())) {
            return;
        }

        if (appointmentImpactAcknowledgement == null
                || appointmentImpactAcknowledgement.isBlank()) {
            log.info("appointment_guard_refused workOrderId={} windowEnd={}",
                    workOrder.getId(), windowEnd);
            throw new BusinessGuardException(
                    "CONFIRMED_APPOINTMENT_BREACH",
                    "Reassignment would breach a confirmed customer appointment window. "
                    + "Provide appointmentImpactAcknowledgement with a reason to proceed.");
        }
    }
}
