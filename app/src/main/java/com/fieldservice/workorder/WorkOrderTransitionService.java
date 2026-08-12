package com.fieldservice.workorder;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.api.dto.TransitionRequest;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

/**
 * Public port for work order lifecycle transitions.
 *
 * <p>All state changes must route through this service; direct mutation of
 * {@link WorkOrder#setState} outside the {@code com.fieldservice.workorder.lifecycle}
 * package is a fitness-test violation.
 */
public interface WorkOrderTransitionService {

    /**
     * Apply {@code event} to the work order identified by {@code workOrderId}.
     * Legacy entry-point used by test helpers and background jobs that manage their own
     * scope context.
     *
     * <p>Coarse authorization gate: only DISPATCHER, TECHNICIAN, and ADMIN may mutate
     * work order state. Per-event role checks are enforced inside the implementation.
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN')")
    WorkOrder applyEvent(UUID workOrderId, WorkOrderEvent event);

    /**
     * HTTP-facing entry-point: applies {@code event} to the work order with full
     * scope enforcement, version pre-check, guard evaluation, outbox event, and
     * structured logging.
     *
     * <p>Coarse authorization gate: only DISPATCHER, TECHNICIAN, and ADMIN may mutate
     * work order state. Per-event role checks are enforced inside the implementation.
     *
     * @param workOrderId     the ID of the work order to transition
     * @param event           the requested lifecycle event
     * @param expectedVersion version the client observed; must match the persisted value
     * @param reason          optional free-text reason supplied by the caller
     * @param holdReasonCode  controlled hold reason code; required for HOLD events by the
     *                        {@code hold-reason-required} guard, ignored for other events
     * @return a {@link TransitionResult} with the updated work order and from-state
     * @throws IllegalWorkOrderTransitionException  if the event is not legal from the current state
     * @throws WorkOrderVersionConflictException    if expectedVersion is stale or a concurrent
     *                                              commit wins the optimistic lock
     * @throws GuardRefusedException                if a guard refuses or throws unexpectedly
     * @throws org.springframework.security.access.AccessDeniedException if the caller's role is not permitted
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN')")
    TransitionResult applyTransition(UUID workOrderId, WorkOrderEvent event,
                                     int expectedVersion, @Nullable String reason,
                                     @Nullable String holdReasonCode,
                                     @Nullable List<TransitionRequest.ShortfallEntry> shortfalls);

    /** Carries the updated work order and the state it was in before the transition. */
    record TransitionResult(WorkOrder workOrder, WorkOrderState fromState) {}
}
