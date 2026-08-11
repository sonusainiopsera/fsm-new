package com.fieldservice.workorder;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;

import java.util.UUID;

/**
 * Public port for work order lifecycle transitions.
 *
 * <p>The single method is the only code path that may legally change a work order's state.
 * All callers — HTTP controllers, background jobs, test helpers — must route through this
 * service; direct mutation of {@link WorkOrder#setState} outside the
 * {@code com.fieldservice.workorder.lifecycle} package is a fitness-test violation.
 */
public interface WorkOrderTransitionService {

    /**
     * Apply {@code event} to the work order identified by {@code workOrderId}.
     *
     * <p>Validates the transition against the immutable table, checks that the current
     * security principal holds a permitted role, evaluates any registered guards in
     * declaration order, then persists the new state.
     *
     * @param workOrderId the ID of the work order to transition
     * @param event       the requested lifecycle event
     * @return the updated, persisted work order
     * @throws IllegalWorkOrderTransitionException if the event is not defined for the
     *         current state; carries {@link WorkOrderErrorCodes#WORK_ORDER_ILLEGAL_TRANSITION}
     * @throws org.springframework.security.access.AccessDeniedException if the caller's
     *         role is not in the permitted set for this transition
     */
    WorkOrder applyEvent(UUID workOrderId, WorkOrderEvent event);
}
