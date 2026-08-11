package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.workorder.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.WorkOrderTransitionService;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sole implementation of {@link WorkOrderTransitionService}.
 *
 * <p>Execution order per call:
 * <ol>
 *   <li>Load the work order (EntityManager.find — bypasses row-scope; transition authority
 *       is separate from read-scope).</li>
 *   <li>Resolve the transition descriptor from the table; throw
 *       {@link IllegalWorkOrderTransitionException} if absent.</li>
 *   <li>Check the caller's GrantedAuthority strings against {@link TransitionDescriptor#requiredRoles()}.</li>
 *   <li>Evaluate registered {@link TransitionGuard} beans in declaration order.</li>
 *   <li>Apply {@link WorkOrder#setState} and persist.</li>
 * </ol>
 */
@Service
@Transactional
public class WorkOrderTransitionServiceImpl implements WorkOrderTransitionService {

    private final EntityManager entityManager;
    private final WorkOrderRepository workOrderRepository;
    private final Map<String, TransitionGuard> guardsByName;

    public WorkOrderTransitionServiceImpl(
            EntityManager entityManager,
            WorkOrderRepository workOrderRepository,
            List<TransitionGuard> guards) {
        this.entityManager = entityManager;
        this.workOrderRepository = workOrderRepository;
        this.guardsByName = guards.stream()
                .collect(Collectors.toMap(TransitionGuard::guardId, Function.identity()));
    }

    @Override
    public WorkOrder applyEvent(UUID workOrderId, WorkOrderEvent event) {
        WorkOrder workOrder = entityManager.find(WorkOrder.class, workOrderId);
        if (workOrder == null) {
            throw new IllegalArgumentException("Work order not found: " + workOrderId);
        }

        Set<WorkOrderEvent> legalEvents = WorkOrderTransitionTable.legalEventsFrom(workOrder.getState());

        TransitionDescriptor descriptor = WorkOrderTransitionTable
                .resolve(workOrder.getState(), event)
                .orElseThrow(() -> new IllegalWorkOrderTransitionException(
                        workOrder.getState(), event, legalEvents));

        checkRole(descriptor.requiredRoles());
        evaluateGuards(workOrder, event, descriptor.guardIds());

        workOrder.setState(descriptor.toState());
        return workOrderRepository.save(workOrder);
    }

    private void checkRole(Set<String> requiredRoles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Collection<String> callerAuthorities = auth == null
                ? List.of()
                : auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

        boolean permitted = requiredRoles.stream().anyMatch(callerAuthorities::contains);
        if (!permitted) {
            throw new AccessDeniedException(
                    "Caller does not hold a permitted role for this transition. Required: " + requiredRoles);
        }
    }

    private void evaluateGuards(WorkOrder workOrder, WorkOrderEvent event, List<String> guardIds) {
        for (String guardId : guardIds) {
            TransitionGuard guard = guardsByName.get(guardId);
            if (guard == null) {
                continue;
            }
            GuardResult result = guard.evaluate(workOrder, event);
            switch (result) {
                case GuardResult.Satisfied ignored -> { /* proceed */ }
                case GuardResult.Refused refused ->
                        throw new IllegalStateException(
                                "Guard '" + guardId + "' refused: [" + refused.code() + "] " + refused.message());
            }
        }
    }
}
