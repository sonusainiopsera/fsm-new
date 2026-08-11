package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderHold;
import com.fieldservice.domain.workorder.WorkOrderHoldRepository;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.workorder.GuardRefusedException;
import com.fieldservice.workorder.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.WorkOrderTransitionService;
import com.fieldservice.workorder.WorkOrderVersionConflictException;
import com.fieldservice.workorder.holds.HoldReasonService;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sole implementation of {@link WorkOrderTransitionService}.
 *
 * <p>{@link #applyEvent} is the legacy entry-point (used by test helpers and background
 * jobs); it loads via EntityManager and does not enforce row-scope.
 *
 * <p>{@link #applyTransition} is the HTTP entry-point used by the transition controller.
 * Execution order per call:
 * <ol>
 *   <li>Load via {@link ScopedQueryExecutor} (row-scope enforced; absent == out-of-scope == 403).</li>
 *   <li>Pre-check client-supplied {@code expectedVersion}.</li>
 *   <li>Resolve the transition descriptor; throw {@link IllegalWorkOrderTransitionException} if absent.</li>
 *   <li>Check the caller's role against {@link TransitionDescriptor#requiredRoles()}.</li>
 *   <li>Evaluate guards fail-closed: any exception → {@link GuardRefusedException}.</li>
 *   <li>Apply state, flush, catch {@link ObjectOptimisticLockingFailureException} →
 *       {@link WorkOrderVersionConflictException}.</li>
 *   <li>Publish {@link WorkOrderStateChangedPayload} outbox event (MANDATORY propagation).</li>
 *   <li>Emit structured log with actor, resource, from/to state, and outcome.</li>
 * </ol>
 */
@Service
@Transactional
public class WorkOrderTransitionServiceImpl implements WorkOrderTransitionService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderTransitionServiceImpl.class);

    private final EntityManager entityManager;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderHoldRepository workOrderHoldRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final AccessScopeResolver scopeResolver;
    private final DomainEventPublisher eventPublisher;
    private final HoldReasonService holdReasonService;
    private final Map<String, TransitionGuard> guardsByName;

    public WorkOrderTransitionServiceImpl(
            EntityManager entityManager,
            WorkOrderRepository workOrderRepository,
            WorkOrderHoldRepository workOrderHoldRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            AccessScopeResolver scopeResolver,
            DomainEventPublisher eventPublisher,
            HoldReasonService holdReasonService,
            List<TransitionGuard> guards) {
        this.entityManager = entityManager;
        this.workOrderRepository = workOrderRepository;
        this.workOrderHoldRepository = workOrderHoldRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.scopeResolver = scopeResolver;
        this.eventPublisher = eventPublisher;
        this.holdReasonService = holdReasonService;
        this.guardsByName = guards.stream()
                .collect(Collectors.toMap(TransitionGuard::guardId, Function.identity()));
    }

    /**
     * Validates at startup that every guard identifier referenced in the transition table
     * has a registered {@link TransitionGuard} bean. A missing implementation fails the
     * context so a mis-configured guard degrades to startup failure, never a silent permit.
     */
    @PostConstruct
    void validateGuardCompleteness() {
        Set<String> missing = WorkOrderTransitionTable.allReferencedGuardIds().stream()
                .filter(id -> !guardsByName.containsKey(id))
                .collect(Collectors.toUnmodifiableSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "WorkOrderTransitionServiceImpl: the following guard identifiers are referenced " +
                    "in the transition table but have no registered TransitionGuard bean: " + missing +
                    ". Register a @Component implementing TransitionGuard for each missing identifier.");
        }
    }

    // ── Legacy entry-point (test helpers, background jobs) ──────────────────────

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
        evaluateGuardsLenient(workOrder, event, descriptor.guardIds());

        workOrder.setState(descriptor.toState());
        return workOrderRepository.save(workOrder);
    }

    // ── HTTP entry-point ────────────────────────────────────────────────────────

    @Override
    public TransitionResult applyTransition(UUID workOrderId, WorkOrderEvent event,
                                            int expectedVersion, @Nullable String reason,
                                            @Nullable String holdReasonCode) {

        // 1. Load via scope (absent == out-of-scope; maps to 403 by non-disclosure contract)
        WorkOrder workOrder = scopedQueryExecutor.findById(WorkOrder.class, workOrderId, workOrderRepository);
        WorkOrderState fromState = workOrder.getState();
        UUID actor = resolveActorId();

        // 2. Fast fail on stale expectedVersion
        if (!Objects.equals(workOrder.getVersion(), expectedVersion)) {
            log.info("transition.refused.version_mismatch: actor={}, workOrderId={}, expected={}, actual={}",
                    actor, workOrderId, expectedVersion, workOrder.getVersion());
            throw new WorkOrderVersionConflictException(workOrderId, expectedVersion);
        }

        // 3. Resolve transition
        Set<WorkOrderEvent> legalEvents = WorkOrderTransitionTable.legalEventsFrom(fromState);
        TransitionDescriptor descriptor = WorkOrderTransitionTable
                .resolve(fromState, event)
                .orElseThrow(() -> {
                    log.info("transition.refused.illegal: actor={}, workOrderId={}, fromState={}, event={}, legal={}",
                            actor, workOrderId, fromState, event, legalEvents);
                    return new IllegalWorkOrderTransitionException(fromState, event, legalEvents);
                });

        // 4. Role check
        checkRole(descriptor.requiredRoles());

        // 5a. Vocabulary validation (HOLD transitions only) — throws 400 before guards run
        if (event == WorkOrderEvent.HOLD && holdReasonCode != null) {
            holdReasonService.validate(holdReasonCode);
        }

        // 5. Guard evaluation — fail-closed: any exception is treated as a refusal
        Instant transitionInstant = Instant.now();
        TransitionContext context = new TransitionContext(holdReasonCode, transitionInstant);
        evaluateGuardsStrict(workOrder, event, descriptor.guardIds(), context);

        // 6. Apply state and flush (catches concurrent-update optimistic lock)
        workOrder.setState(descriptor.toState());

        // 6a. Hold interval management (inside the transaction so audit and hold data cannot diverge)
        handleHoldInterval(workOrder, fromState, event, actor, holdReasonCode, reason, transitionInstant);

        WorkOrder saved;
        try {
            saved = workOrderRepository.save(workOrder);
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.info("transition.refused.concurrent_lock: actor={}, workOrderId={}", actor, workOrderId);
            throw new WorkOrderVersionConflictException(workOrderId, expectedVersion);
        }

        // 7. Publish outbox event (MANDATORY — same transaction; rolls back with state if it fails)
        publishStateChangedEvent(saved, fromState, actor);

        log.info("transition.applied: actor={}, workOrderId={}, fromState={}, event={}, toState={}, version={}",
                actor, workOrderId, fromState, event, descriptor.toState(), saved.getVersion());

        return new TransitionResult(saved, fromState);
    }

    // ── Hold interval management ─────────────────────────────────────────────────

    /**
     * Manages hold interval records when HOLD, RESUME, or a dangling-close event fires.
     *
     * <p>HOLD: inserts a new open hold record.
     * RESUME / state change away from ON_HOLD: closes any open hold and accumulates minutes.
     *
     * <p>All DB writes are inside the caller's transaction so hold data and work order
     * audit records cannot diverge.
     */
    private void handleHoldInterval(WorkOrder workOrder, WorkOrderState fromState,
                                    WorkOrderEvent event, @Nullable UUID actor,
                                    @Nullable String holdReasonCode, @Nullable String note,
                                    Instant transitionInstant) {
        if (event == WorkOrderEvent.HOLD) {
            WorkOrderHold hold = new WorkOrderHold();
            hold.setWorkOrderId(workOrder.getId());
            hold.setReasonCode(holdReasonCode);
            hold.setNote(note);
            hold.setStartedAt(transitionInstant);
            hold.setStartedBy(actor);
            workOrderHoldRepository.save(hold);

        } else if (fromState == WorkOrderState.ON_HOLD) {
            Optional<WorkOrderHold> openHold =
                    workOrderHoldRepository.findByWorkOrderIdAndEndedAtIsNull(workOrder.getId());
            openHold.ifPresent(hold -> {
                hold.setEndedAt(transitionInstant);
                hold.setEndedBy(actor);
                workOrderHoldRepository.save(hold);

                long elapsedMinutes = computeHoldMinutes(hold.getStartedAt(), transitionInstant);
                workOrder.addHoldMinutes((int) elapsedMinutes);
            });
        }
    }

    /**
     * Computes elapsed hold minutes, rounding sub-minute holds up to 1 so cumulative
     * totals are never negative and sub-minute holds are not silently discarded.
     * An ended_at earlier than started_at (clock skew) is treated as zero.
     */
    public static long computeHoldMinutes(Instant startedAt, Instant endedAt) {
        long seconds = Duration.between(startedAt, endedAt).getSeconds();
        if (seconds <= 0) {
            return 0L;
        }
        // Ceiling division: any partial minute counts as 1
        return (seconds + 59) / 60;
    }

    // ── Guards ──────────────────────────────────────────────────────────────────

    /** Used by the HTTP entry-point. Any exception from a guard is treated as a refusal. */
    private void evaluateGuardsStrict(WorkOrder workOrder, WorkOrderEvent event,
                                       List<String> guardIds, TransitionContext context) {
        for (String guardId : guardIds) {
            TransitionGuard guard = guardsByName.get(guardId);
            if (guard == null) {
                continue;
            }
            GuardResult result;
            try {
                result = guard.evaluate(workOrder, event, context);
            } catch (Exception ex) {
                log.warn("guard.exception: guardId={}, workOrderId={}", guardId, workOrder.getId(), ex);
                throw new GuardRefusedException(guardId, "GUARD_EXCEPTION", "Guard evaluation failed.");
            }
            switch (result) {
                case GuardResult.Satisfied ignored -> { /* proceed */ }
                case GuardResult.Refused refused ->
                        throw new GuardRefusedException(guardId, refused.code(), refused.message());
            }
        }
    }

    /** Used by the legacy entry-point. Preserves the original behaviour of throwing IllegalStateException. */
    private void evaluateGuardsLenient(WorkOrder workOrder, WorkOrderEvent event, List<String> guardIds) {
        TransitionContext context = TransitionContext.of(null);
        for (String guardId : guardIds) {
            TransitionGuard guard = guardsByName.get(guardId);
            if (guard == null) {
                continue;
            }
            GuardResult result = guard.evaluate(workOrder, event, context);
            switch (result) {
                case GuardResult.Satisfied ignored -> { /* proceed */ }
                case GuardResult.Refused refused ->
                        throw new IllegalStateException(
                                "Guard '" + guardId + "' refused: [" + refused.code() + "] " + refused.message());
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

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

    private void publishStateChangedEvent(WorkOrder workOrder, WorkOrderState fromState, @Nullable UUID actor) {
        var payload = new WorkOrderStateChangedPayload(
                workOrder.getId(),
                fromState.name(),
                workOrder.getState().name(),
                workOrder.getPriority() != null ? workOrder.getPriority().name() : null,
                Instant.now());
        Map<String, Object> payloadMap = PiiRedactionUtility.toPayloadMap(payload);
        DomainEvent domainEvent = DomainEvent.of(
                WorkOrderStateChangedPayload.EVENT_TYPE,
                WorkOrderStateChangedPayload.AGGREGATE_TYPE,
                workOrder.getId(),
                Instant.now(),
                MDC.get("traceId"),
                actor,
                payloadMap);
        eventPublisher.publish(domainEvent);
    }

    private UUID resolveActorId() {
        try {
            AccessScope scope = scopeResolver.resolve();
            return scope.userId();
        } catch (Exception ex) {
            return null;
        }
    }
}
