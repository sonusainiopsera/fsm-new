package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.LabourTimeRecordRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.springframework.stereotype.Component;

/**
 * Refuses COMPLETE when no labour time has been recorded against the work order (BR-08).
 *
 * <p>At least one {@link com.fieldservice.domain.workorder.LabourTimeRecord} row must exist
 * for the work order. A work order where all labour records were later deleted will again
 * fail this guard.
 */
@Component
public class LabourTimeRecordedGuard implements TransitionGuard {

    public static final String GUARD_ID = "labour-time-recorded";

    private final LabourTimeRecordRepository labourTimeRecordRepository;

    public LabourTimeRecordedGuard(LabourTimeRecordRepository labourTimeRecordRepository) {
        this.labourTimeRecordRepository = labourTimeRecordRepository;
    }

    @Override
    public String guardId() {
        return GUARD_ID;
    }

    @Override
    public GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event, TransitionContext context) {
        boolean hasLabour = labourTimeRecordRepository.existsByWorkOrderId(workOrder.getId());
        if (!hasLabour) {
            return new GuardResult.Refused(
                    "LABOUR_TIME_MISSING",
                    "Work order " + workOrder.getId() + " cannot be completed: no labour time has been recorded. " +
                    "Log at least one labour time entry before marking this work order complete.");
        }
        return new GuardResult.Satisfied();
    }
}
