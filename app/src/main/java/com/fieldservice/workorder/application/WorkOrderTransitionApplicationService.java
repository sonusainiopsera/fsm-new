package com.fieldservice.workorder.application;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.lifecycle.TransitionDescriptor;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionService;
import com.fieldservice.workorder.lifecycle.WorkOrderVersionConflictException;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.web.TransitionRequest;
import com.fieldservice.workorder.web.TransitionResponse;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service orchestrating a work order lifecycle transition.
 *
 * <p>Single transactional boundary that: loads the aggregate via access-scoped repository,
 * validates expectedVersion, resolves the transition from the table, enforces roles, runs
 * ordered guards, applies state, persists, publishes the outbox event, and returns the
 * new state snapshot. Envers writes the revision automatically on flush.
 */
@Service
public class WorkOrderTransitionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderTransitionApplicationService.class);

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final RequestScopedAccessScope accessScope;
    private final WorkOrderTransitionService transitionService;
    private final DomainEventPublisher eventPublisher;
    private final List<TransitionGuard> guards;
    private final EntityManager entityManager;

    public WorkOrderTransitionApplicationService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            RequestScopedAccessScope accessScope,
            WorkOrderTransitionService transitionService,
            DomainEventPublisher eventPublisher,
            List<TransitionGuard> guards,
            EntityManager entityManager) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
        this.accessScope = accessScope;
        this.transitionService = transitionService;
        this.eventPublisher = eventPublisher;
        this.guards = guards;
        this.entityManager = entityManager;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER', 'TECHNICIAN')")
    @Transactional
    public TransitionResponse apply(UUID workOrderId,
                                    TransitionRequest request,
                                    Authentication authentication,
                                    Instant occurredAt) {

        AccessScope scope = accessScope.get();
        List<String> actorRoles = extractRoles(authentication);

        // 1. Load via scoped repository — out-of-scope or missing id → 403
        WorkOrder workOrder = scopedQueryExecutor
                .findById(workOrderRepository, workOrderId, scope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "work_order", "Resource not found or outside caller scope"));

        WorkOrderState fromState = WorkOrderState.valueOf(workOrder.getState().name());

        // 2. Fail-fast version check before any expensive work
        if (!Objects.equals(workOrder.getVersion(), request.expectedVersion())) {
            log.info("transition_refused work_order_id={} from_state={} event={} outcome=VERSION_CONFLICT actor={}",
                    workOrderId, fromState, request.event(), scope.userId());
            throw new WorkOrderVersionConflictException(
                    "Expected version " + request.expectedVersion()
                            + " but entity is at version " + workOrder.getVersion());
        }

        // 3. Resolve transition from the table
        Set<WorkOrderEvent> legalEvents = transitionService.legalEventsFrom(fromState);
        TransitionDescriptor descriptor = transitionService.resolve(fromState, request.event())
                .orElseThrow(() -> {
                    log.info("transition_refused work_order_id={} from_state={} event={} "
                                    + "outcome=ILLEGAL_TRANSITION actor={}",
                            workOrderId, fromState, request.event(), scope.userId());
                    return new IllegalWorkOrderTransitionException(fromState, request.event(), legalEvents);
                });

        // 4. Enforce role
        boolean authorized = descriptor.requiredRoles().stream().anyMatch(actorRoles::contains);
        if (!authorized) {
            log.info("transition_refused work_order_id={} from_state={} event={} outcome=FORBIDDEN actor={}",
                    workOrderId, fromState, request.event(), scope.userId());
            throw new ForbiddenException(
                    "Role not permitted for event " + request.event() + " from state " + fromState);
        }

        // 5. Run ordered guards (fail closed — any exception is treated as refusal)
        runGuards(descriptor.guardIds(), fromState, request.event(), request, workOrderId, scope.userId());

        // 6. Apply state transition
        WorkOrderStatus newStatus = WorkOrderStatus.valueOf(descriptor.toState().name());
        workOrder.applyStateTransition(newStatus);
        WorkOrderState toState = descriptor.toState();

        // 7. Persist and flush to surface any concurrent-modification conflict within this transaction
        workOrderRepository.save(workOrder);
        try {
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("transition_concurrent_conflict work_order_id={} actor={}", workOrderId, scope.userId());
            throw new WorkOrderVersionConflictException("Concurrent modification detected; retry with latest version", e);
        }

        // 8. Publish outbox event within the same transaction — Envers revision written on commit
        WorkOrderTransitionPayload payload = new WorkOrderTransitionPayload(
                workOrder.getId(),
                fromState.name(),
                toState.name(),
                request.event().name(),
                scope.userId(),
                request.reason());

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "WORK_ORDER_STATE_CHANGED",
                "WORK_ORDER",
                workOrder.getId(),
                occurredAt,
                MDC.get("traceId"),
                scope.userId(),
                payload));

        Set<WorkOrderEvent> legalNextEvents = transitionService.legalEventsFrom(toState);

        log.info("transition_applied work_order_id={} from_state={} event={} to_state={} version={} actor={}",
                workOrderId, fromState, request.event(), toState, workOrder.getVersion(), scope.userId());

        return TransitionResponse.of(workOrder.getId(), fromState, toState,
                workOrder.getVersion(), legalNextEvents, occurredAt);
    }

    private void runGuards(List<String> guardIds,
                            WorkOrderState fromState,
                            WorkOrderEvent event,
                            TransitionRequest request,
                            UUID workOrderId,
                            UUID actorId) {
        for (String guardId : guardIds) {
            TransitionGuard guard = guards.stream()
                    .filter(g -> guardId.equals(g.guardId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessGuardException("Guard not configured: " + guardId));

            GuardResult result;
            try {
                result = guard.evaluate(fromState, event, request);
            } catch (BusinessGuardException e) {
                throw e;
            } catch (Exception e) {
                log.warn("guard_exception work_order_id={} guard_id={} actor={}",
                        workOrderId, guardId, actorId, e);
                throw new BusinessGuardException("Guard check failed for: " + guardId);
            }

            if (result instanceof GuardResult.Refused refused) {
                log.info("guard_refused work_order_id={} guard_id={} code={} actor={}",
                        workOrderId, guardId, refused.code(), actorId);
                throw new BusinessGuardException(refused.message());
            }
        }
    }

    private static List<String> extractRoles(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toList());
    }
}
