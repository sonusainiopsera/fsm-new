package com.fieldservice.workorder.lifecycle;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Computes the set of events a given role may trigger from a given work order state.
 *
 * <p>This is the single gateway through which any caller determines available actions —
 * neither controllers nor services may branch on state directly.
 */
@Component
public class AllowedTransitionResolver {

    /**
     * Returns the event names (as strings) that {@code role} may trigger when the work order
     * is in {@code state}. The result is filtered from the transition table and is safe to
     * embed in a client response.
     *
     * @param state current work order state
     * @param role  Spring Security authority string, e.g. {@code "TECHNICIAN"}
     * @return ordered list of event names; empty for terminal states or unknown roles
     */
    public List<String> allowedEvents(WorkOrderState state, String role) {
        Set<WorkOrderEvent> all = WorkOrderTransitionTable.legalEventsFrom(state);
        return all.stream()
                .filter(event -> {
                    var descriptor = WorkOrderTransitionTable.resolve(state, event);
                    return descriptor.isPresent() && descriptor.get().requiredRoles().contains(role);
                })
                .map(WorkOrderEvent::name)
                .sorted()
                .toList();
    }
}
