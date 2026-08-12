package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.inventory.repository.WorkOrderPartsConsumptionRepository;
import com.fieldservice.workorder.lifecycle.GuardContext;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import org.springframework.stereotype.Component;

@Component
public class PartsReconciledGuard implements TransitionGuard {

    public static final String GUARD_ID = "parts.reconciled";

    private final WorkOrderPartsConsumptionRepository partsRepository;

    public PartsReconciledGuard(WorkOrderPartsConsumptionRepository partsRepository) {
        this.partsRepository = partsRepository;
    }

    @Override
    public String guardId() { return GUARD_ID; }

    @Override
    public GuardResult evaluate(WorkOrderState fromState, WorkOrderEvent event, Object context) {
        GuardContext ctx = (GuardContext) context;
        if (partsRepository.existsByWorkOrderIdAndReconciledFalse(ctx.workOrderId())) {
            String action = (event == WorkOrderEvent.COMPLETE) ? "completing" : "closing";
            return new GuardResult.Refused(
                    "PARTS_UNRECONCILED",
                    "All parts consumption records must be reconciled before " + action
                    + " this work order. Review unreconciled parts entries and confirm or adjust quantities.");
        }
        return new GuardResult.Satisfied();
    }
}
