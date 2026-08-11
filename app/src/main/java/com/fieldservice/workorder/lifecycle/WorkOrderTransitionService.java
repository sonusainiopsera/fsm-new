package com.fieldservice.workorder.lifecycle;

import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public lifecycle port. All callers must go through this service; no other class
 * may mutate {@link WorkOrder#applyStateTransition} directly.
 */
@Component
public class WorkOrderTransitionService {

    /**
     * Validates the event against the table and the actor's roles, then applies the
     * transition to the entity. Callers are responsible for persisting the entity.
     *
     * @param workOrder   the aggregate root to transition
     * @param event       the requested lifecycle event
     * @param actorRoles  Spring Security authority strings held by the caller
     * @return the new persisted-status value
     * @throws IllegalWorkOrderTransitionException if the event is not legal from the current state
     * @throws ForbiddenException                  if the caller lacks a required role
     */
    public WorkOrderStatus apply(WorkOrder workOrder, WorkOrderEvent event,
                                 Collection<String> actorRoles) {
        WorkOrderState fromState = WorkOrderState.valueOf(workOrder.getState().name());
        Set<WorkOrderEvent> legalEvents = WorkOrderTransitionTable.legalEventsFrom(fromState);

        TransitionDescriptor descriptor = WorkOrderTransitionTable.resolve(fromState, event)
                .orElseThrow(() -> new IllegalWorkOrderTransitionException(
                        fromState, event, legalEvents));

        boolean authorised = descriptor.requiredRoles().stream().anyMatch(actorRoles::contains);
        if (!authorised) {
            throw new ForbiddenException(
                    "Role not permitted for event " + event + " from state " + fromState);
        }

        WorkOrderStatus newStatus = WorkOrderStatus.valueOf(descriptor.toState().name());
        workOrder.applyStateTransition(newStatus);
        return newStatus;
    }

    public Set<WorkOrderEvent> legalEventsFrom(WorkOrderState state) {
        return WorkOrderTransitionTable.legalEventsFrom(state);
    }

    public Optional<TransitionDescriptor> resolve(WorkOrderState fromState, WorkOrderEvent event) {
        return WorkOrderTransitionTable.resolve(fromState, event);
    }

    /** Returns every guard identifier referenced in the transition table — for startup validation. */
    public Set<String> allReferencedGuardIds() {
        return WorkOrderTransitionTable.rawTable().values().stream()
                .flatMap(d -> d.guardIds().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
