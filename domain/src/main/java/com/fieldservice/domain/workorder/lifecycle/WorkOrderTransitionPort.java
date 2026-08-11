package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.util.Optional;
import java.util.Set;

/**
 * Public read interface for the work-order transition table.
 * The implementation ({@code WorkOrderTransitionTable}) is package-private;
 * callers outside the package obtain an instance via
 * {@link WorkOrderTransitionService} or, in tests only, via
 * {@code WorkOrderTransitionTableAccessor}.
 */
public interface WorkOrderTransitionPort {

    /**
     * Resolve the transition descriptor for the given state and event.
     *
     * @return the descriptor if the transition is defined, or empty if it is illegal
     */
    Optional<TransitionDescriptor> resolve(WorkOrderState fromState, WorkOrderEvent event);

    /**
     * Return all events that are legal from the given state.
     */
    Set<WorkOrderEvent> legalEventsFrom(WorkOrderState fromState);
}
