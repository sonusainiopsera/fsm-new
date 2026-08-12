package com.fieldservice.portal.history;

import com.fieldservice.workorder.domain.WorkOrderStatus;

import java.util.List;

/**
 * Customer-facing status group filter for the portal service history endpoint.
 *
 * <p>Maps a coarse two-state label to the underlying lifecycle state set so the
 * client does not need to know internal state names.
 *
 * <ul>
 *   <li>{@link #OPEN}  — work order is active (NEW through ON_HOLD inclusive)</li>
 *   <li>{@link #CLOSED} — work order reached a terminal state</li>
 * </ul>
 */
public enum StatusGroup {

    OPEN(List.of(
            WorkOrderStatus.NEW,
            WorkOrderStatus.ASSIGNED,
            WorkOrderStatus.EN_ROUTE,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.ON_HOLD)),

    CLOSED(List.of(
            WorkOrderStatus.COMPLETED,
            WorkOrderStatus.CLOSED,
            WorkOrderStatus.CANCELLED));

    private final List<WorkOrderStatus> states;

    StatusGroup(List<WorkOrderStatus> states) {
        this.states = states;
    }

    public List<WorkOrderStatus> getStates() {
        return states;
    }
}
