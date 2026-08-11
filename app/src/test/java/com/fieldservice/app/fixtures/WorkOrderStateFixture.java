package com.fieldservice.app.fixtures;

import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link WorkOrder} instances pre-positioned at any lifecycle state for use in
 * downstream tests. Not Spring-managed; construct with a shared {@link WorkOrderTransitionService}
 * or use the static {@link #inState(WorkOrderStatus, WorkOrder)} helper for unit tests.
 */
public final class WorkOrderStateFixture {

    private static final Map<WorkOrderStatus, List<WorkOrderEvent>> PATHS_FROM_NEW =
            buildPaths();

    private final WorkOrderTransitionService transitionService;
    private final List<String> actorRoles = List.of("DISPATCHER", "TECHNICIAN", "ADMIN", "MANAGER");

    public WorkOrderStateFixture(WorkOrderTransitionService transitionService) {
        this.transitionService = transitionService;
    }

    /**
     * Advances the given work order from its current state to {@code targetStatus} by
     * applying the canonical event sequence. The caller is responsible for persisting.
     */
    public void advanceTo(WorkOrder workOrder, WorkOrderStatus targetStatus) {
        WorkOrderStatus current = workOrder.getState();
        if (current == targetStatus) return;

        List<WorkOrderEvent> path = PATHS_FROM_NEW.get(targetStatus);
        if (path == null) {
            throw new IllegalArgumentException("No fixture path to terminal state: " + targetStatus);
        }

        // Apply only events needed from the current position
        WorkOrderState currentState = WorkOrderState.valueOf(current.name());
        boolean applying = (current == WorkOrderStatus.NEW);

        for (WorkOrderEvent event : path) {
            if (!applying) {
                var descriptor = transitionService.resolve(currentState, event);
                if (descriptor.isPresent()) {
                    applying = true;
                }
            }
            if (applying) {
                transitionService.apply(workOrder, event, actorRoles);
                currentState = WorkOrderState.valueOf(workOrder.getState().name());
                if (workOrder.getState() == targetStatus) break;
            }
        }
    }

    /**
     * Unit-test helper: directly sets the entity state field via {@code applyStateTransition}
     * without any lifecycle validation. Use only in unit tests where Spring context is absent.
     */
    public static WorkOrder inState(WorkOrderStatus status, WorkOrder workOrder) {
        workOrder.applyStateTransition(status);
        return workOrder;
    }

    private static Map<WorkOrderStatus, List<WorkOrderEvent>> buildPaths() {
        Map<WorkOrderStatus, List<WorkOrderEvent>> map = new EnumMap<>(WorkOrderStatus.class);
        map.put(WorkOrderStatus.NEW,         List.of());
        map.put(WorkOrderStatus.ASSIGNED,    List.of(WorkOrderEvent.ASSIGN));
        map.put(WorkOrderStatus.EN_ROUTE,    List.of(WorkOrderEvent.ASSIGN, WorkOrderEvent.DEPART));
        map.put(WorkOrderStatus.IN_PROGRESS, List.of(WorkOrderEvent.ASSIGN, WorkOrderEvent.DEPART,
                                                     WorkOrderEvent.START));
        map.put(WorkOrderStatus.ON_HOLD,     List.of(WorkOrderEvent.ASSIGN, WorkOrderEvent.DEPART,
                                                     WorkOrderEvent.START, WorkOrderEvent.HOLD));
        map.put(WorkOrderStatus.COMPLETED,   List.of(WorkOrderEvent.ASSIGN, WorkOrderEvent.DEPART,
                                                     WorkOrderEvent.START, WorkOrderEvent.COMPLETE));
        map.put(WorkOrderStatus.CLOSED,      List.of(WorkOrderEvent.ASSIGN, WorkOrderEvent.DEPART,
                                                     WorkOrderEvent.START, WorkOrderEvent.COMPLETE,
                                                     WorkOrderEvent.CLOSE));
        map.put(WorkOrderStatus.CANCELLED,   List.of(WorkOrderEvent.CANCEL));
        return map;
    }
}
