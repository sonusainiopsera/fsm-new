package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.springframework.stereotype.Component;

/**
 * Refuses HOLD when no controlled hold reason code is supplied.
 *
 * <p>Vocabulary validation (whether the code is from the approved list) is deferred
 * to the hold-reason story. This guard only checks that a non-blank code is present.
 */
@Component
public class HoldReasonRequiredGuard implements TransitionGuard {

    public static final String GUARD_ID = "hold-reason-required";

    @Override
    public String guardId() {
        return GUARD_ID;
    }

    @Override
    public GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event, TransitionContext context) {
        String holdReasonCode = context.holdReasonCode();
        if (holdReasonCode == null || holdReasonCode.isBlank()) {
            return new GuardResult.Refused(
                    "HOLD_REASON_MISSING",
                    "Work order " + workOrder.getId() + " cannot be placed on hold: a hold reason code " +
                    "is required. Supply a holdReasonCode in the transition request.");
        }
        return new GuardResult.Satisfied();
    }
}
