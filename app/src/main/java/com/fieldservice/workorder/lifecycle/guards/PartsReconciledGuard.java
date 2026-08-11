package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.inventory.api.WorkOrderConsumptionQueryPort;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.TransitionContext;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.springframework.stereotype.Component;

/**
 * Refuses CLOSE when any parts-consumption record on the work order is unreconciled.
 *
 * <p>A work order with zero consumption records is considered fully reconciled and passes.
 * Consults the inventory module's public read interface; never accesses inventory tables directly.
 */
@Component
public class PartsReconciledGuard implements TransitionGuard {

    public static final String GUARD_ID = "parts-reconciled";

    private final WorkOrderConsumptionQueryPort consumptionQueryPort;

    public PartsReconciledGuard(WorkOrderConsumptionQueryPort consumptionQueryPort) {
        this.consumptionQueryPort = consumptionQueryPort;
    }

    @Override
    public String guardId() {
        return GUARD_ID;
    }

    @Override
    public GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event, TransitionContext context) {
        boolean hasUnreconciled = consumptionQueryPort.hasUnreconciledConsumption(workOrder.getId());
        if (hasUnreconciled) {
            return new GuardResult.Refused(
                    "PARTS_UNRECONCILED",
                    "Work order " + workOrder.getId() + " cannot be closed: one or more parts consumption " +
                    "records are unreconciled. Reconcile all parts consumption before closing.");
        }
        return new GuardResult.Satisfied();
    }
}
