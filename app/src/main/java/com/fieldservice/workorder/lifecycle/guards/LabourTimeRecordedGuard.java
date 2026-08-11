package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.workorder.lifecycle.GuardContext;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.repository.LabourEntryRepository;
import org.springframework.stereotype.Component;

@Component
public class LabourTimeRecordedGuard implements TransitionGuard {

    public static final String GUARD_ID = "labour.time.recorded";

    private final LabourEntryRepository labourEntryRepository;

    public LabourTimeRecordedGuard(LabourEntryRepository labourEntryRepository) {
        this.labourEntryRepository = labourEntryRepository;
    }

    @Override
    public String guardId() { return GUARD_ID; }

    @Override
    public GuardResult evaluate(WorkOrderState fromState, WorkOrderEvent event, Object context) {
        GuardContext ctx = (GuardContext) context;
        if (!labourEntryRepository.existsByWorkOrderId(ctx.workOrderId())) {
            return new GuardResult.Refused(
                    "LABOUR_TIME_MISSING",
                    "At least one labour time entry must be recorded before completing this work order.");
        }
        return new GuardResult.Satisfied();
    }
}
