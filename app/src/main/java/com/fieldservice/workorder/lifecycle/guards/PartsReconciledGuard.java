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
            return new GuardResult.Refused(
                    "PARTS_UNRECONCILED",
                    "All parts consumption records must be reconciled before closing this work order.");
        }
        return new GuardResult.Satisfied();
    }
}
