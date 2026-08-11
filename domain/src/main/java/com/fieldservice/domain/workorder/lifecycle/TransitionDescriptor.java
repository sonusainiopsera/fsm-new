package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.util.List;
import java.util.Set;

/**
 * Resolved target of a lifecycle transition lookup.
 *
 * <ul>
 *   <li>{@code toState} — the state to move the work order into</li>
 *   <li>{@code requiredRoles} — Spring Security authority strings; the caller must hold at least one</li>
 *   <li>{@code guardIds} — ordered guard identifiers to evaluate before the transition may proceed;
 *       evaluated left-to-right, first refusal wins</li>
 * </ul>
 */
public record TransitionDescriptor(
        WorkOrderState toState,
        Set<String> requiredRoles,
        List<String> guardIds) {

    public TransitionDescriptor {
        requiredRoles = Set.copyOf(requiredRoles);
        guardIds = List.copyOf(guardIds);
    }
}
