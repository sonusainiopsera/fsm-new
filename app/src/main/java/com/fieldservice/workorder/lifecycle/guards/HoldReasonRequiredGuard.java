package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.workorder.lifecycle.GuardContext;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import org.springframework.stereotype.Component;

@Component
public class HoldReasonRequiredGuard implements TransitionGuard {

    public static final String GUARD_ID = "hold.reason.required";

    @Override
    public String guardId() { return GUARD_ID; }

    @Override
    public GuardResult evaluate(WorkOrderState fromState, WorkOrderEvent event, Object context) {
        GuardContext ctx = (GuardContext) context;
        if (ctx.holdReasonCode() == null) {
            return new GuardResult.Refused(
                    "HOLD_REASON_MISSING",
                    "A controlled hold reason code is required to place a work order on hold.");
        }
        return new GuardResult.Satisfied();
    }
}
