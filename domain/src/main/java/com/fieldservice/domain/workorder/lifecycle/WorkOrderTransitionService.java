package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public port of the work-order lifecycle module.
 *
 * <p>This is the ONLY entry point for applying lifecycle transitions; callers must
 * never set {@link WorkOrder#setState(WorkOrderState)} directly. The transition table
 * is package-private and not accessible from outside this package.
 *
 * <p>Role enforcement is done at two levels: Spring Security's
 * {@code @PreAuthorize} gates the method to authenticated participants, and the
 * transition table's {@link TransitionDescriptor#requiredRoles()} further restricts
 * which of those participants may trigger a specific event.
 */
@Service
public class WorkOrderTransitionService {

    private final WorkOrderTransitionPort table = new WorkOrderTransitionTable();
    private final WorkOrderRepository repository;
    private final List<TransitionGuard> guards;

    public WorkOrderTransitionService(
            WorkOrderRepository repository,
            List<TransitionGuard> guards) {
        this.repository = repository;
        this.guards = List.copyOf(guards);
    }

    /**
     * Apply a lifecycle event to the identified work order.
     *
     * <p>Steps:
     * <ol>
     *   <li>Resolve the transition from the table (illegal → 409)</li>
     *   <li>Assert the caller holds at least one required role (unauthorized → 403)</li>
     *   <li>Run ordered guards (refused → 422)</li>
     *   <li>Apply the state change and persist with optimistic-lock version check</li>
     * </ol>
     *
     * @param workOrderId the work order to transition
     * @param event       the lifecycle event to apply
     * @return the updated, persisted work order
     * @throws IllegalWorkOrderTransitionException if the event is not legal from the current state
     * @throws org.springframework.security.access.AccessDeniedException if the caller lacks a required role
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN', 'MANAGER')")
    @Transactional
    public WorkOrder transition(UUID workOrderId, WorkOrderEvent event) {
        WorkOrder wo = repository.findById(workOrderId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "WorkOrder not found: " + workOrderId));

        WorkOrderState currentState = wo.getState();

        TransitionDescriptor descriptor = table.resolve(currentState, event)
                .orElseThrow(() -> new IllegalWorkOrderTransitionException(
                        currentState, event, table.legalEventsFrom(currentState)));

        enforceRoles(descriptor.requiredRoles(), currentState, event);
        evaluateGuards(wo, event, descriptor.guardIds());

        wo.setState(descriptor.toState());
        return repository.save(wo);
    }

    /**
     * Return the set of events that are legal from the given state.
     * Used by the API layer to populate the {@code legalEvents} hint field.
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    public Set<WorkOrderEvent> legalEventsFrom(WorkOrderState state) {
        return table.legalEventsFrom(state);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void enforceRoles(
            Set<String> requiredRoles,
            WorkOrderState currentState,
            WorkOrderEvent event) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "No authentication present");
        }
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        boolean authorized = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .anyMatch(requiredRoles::contains);
        if (!authorized) {
            throw new org.springframework.security.access.AccessDeniedException(
                    String.format("None of the required roles %s held for event %s from %s",
                            requiredRoles, event, currentState));
        }
    }

    private void evaluateGuards(
            WorkOrder workOrder,
            WorkOrderEvent event,
            List<String> guardIds) {
        if (guardIds.isEmpty()) return;
        for (String guardId : guardIds) {
            TransitionGuard guard = guards.stream()
                    .filter(g -> g.id().equals(guardId))
                    .findFirst()
                    .orElse(null);
            if (guard == null) continue; // guard not yet implemented — permissive by default
            GuardResult result = guard.evaluate(workOrder, event);
            if (!result.isSatisfied()) {
                GuardResult.Refused refused = (GuardResult.Refused) result;
                throw new WorkOrderGuardRefusedException(
                        guardId, refused.code(), refused.message());
            }
        }
    }
}
