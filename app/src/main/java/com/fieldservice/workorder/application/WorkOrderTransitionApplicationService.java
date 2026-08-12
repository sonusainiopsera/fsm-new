package com.fieldservice.workorder.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.inventory.api.AvailabilityStatus;
import com.fieldservice.inventory.api.CandidateAvailability;
import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.StockQueryService;
import com.fieldservice.inventory.application.ReplenishmentNeededPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.domain.Assignment;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.lifecycle.GuardContext;
import com.fieldservice.workorder.lifecycle.GuardResult;
import com.fieldservice.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.lifecycle.TransitionDescriptor;
import com.fieldservice.workorder.lifecycle.TransitionGuard;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionService;
import com.fieldservice.sla.internal.SlaBreachService;
import com.fieldservice.sla.internal.SlaPolicyService;
import com.fieldservice.workorder.holds.HoldReason;
import com.fieldservice.workorder.holds.HoldReasonService;
import com.fieldservice.workorder.holds.HoldReasonValidationException;
import com.fieldservice.workorder.holds.WorkOrderHold;
import com.fieldservice.workorder.holds.WorkOrderHoldRepository;
import com.fieldservice.workorder.lifecycle.WorkOrderVersionConflictException;
import com.fieldservice.workorder.repository.AssignmentRepository;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.web.AssignmentWarning;
import com.fieldservice.workorder.web.TransitionRequest;
import com.fieldservice.workorder.web.TransitionResponse;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final HoldReasonService holdReasonService;
    private final WorkOrderHoldRepository workOrderHoldRepository;
    private final SlaPolicyService slaPolicyService;
    private final SlaBreachService slaBreachService;
    private final StockQueryService stockQueryService;
    private final AssignmentRepository assignmentRepository;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;

    public WorkOrderTransitionApplicationService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            RequestScopedAccessScope accessScope,
            WorkOrderTransitionService transitionService,
            DomainEventPublisher eventPublisher,
            List<TransitionGuard> guards,
            EntityManager entityManager,
            HoldReasonService holdReasonService,
            WorkOrderHoldRepository workOrderHoldRepository,
            SlaPolicyService slaPolicyService,
            SlaBreachService slaBreachService,
            StockQueryService stockQueryService,
            AssignmentRepository assignmentRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.scopedQueryExecutor      = scopedQueryExecutor;
        this.workOrderRepository      = workOrderRepository;
        this.accessScope              = accessScope;
        this.transitionService        = transitionService;
        this.eventPublisher           = eventPublisher;
        this.guards                   = guards;
        this.entityManager            = entityManager;
        this.holdReasonService        = holdReasonService;
        this.workOrderHoldRepository  = workOrderHoldRepository;
        this.slaPolicyService         = slaPolicyService;
        this.slaBreachService         = slaBreachService;
        this.stockQueryService        = stockQueryService;
        this.assignmentRepository     = assignmentRepository;
        this.namedJdbc                = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.objectMapper             = objectMapper;
    }

    @PostConstruct
    void validateGuardRegistry() {
        Set<String> registeredIds = guards.stream()
                .map(TransitionGuard::guardId)
                .collect(Collectors.toUnmodifiableSet());
        transitionService.allReferencedGuardIds().forEach(id -> {
            if (!registeredIds.contains(id)) {
                throw new IllegalStateException(
                        "Transition table references guard '" + id + "' but no bean implements it. " +
                        "Application startup refused.");
            }
        });
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

        // 4.5 Vocabulary validation for HOLD — must precede guards so invalid codes return 400, not 422
        if (request.event() == WorkOrderEvent.HOLD) {
            holdReasonService.validate(request.holdReasonCode());
        }

        // 5. Run ordered guards (fail closed — any exception is treated as refusal)
        runGuards(descriptor.guardIds(), fromState, request.event(), request, workOrderId, scope.userId(), occurredAt);

        // 5.5 Hold interval management (all within this transaction)
        applyHoldIntervalChanges(request, workOrderId, fromState, scope.userId(), occurredAt,
                workOrder, legalEvents);

        // 5.7 AWAITING_PARTS hold: record reason code on work order (BR-14) and signal replenishment
        if (request.event() == WorkOrderEvent.HOLD
                && "AWAITING_PARTS".equals(request.holdReasonCode())) {
            workOrder.setPartsUnavailabilityReason("AWAITING_PARTS");
            emitReplenishmentNeeded(workOrderId, scope.userId(), occurredAt);
        }

        // 5.6 Parts availability pre-check (advisory — never a hard gate)
        List<AssignmentWarning> assignmentWarnings = List.of();
        if (request.event() == WorkOrderEvent.ASSIGN && request.technicianId() != null) {
            assignmentWarnings = checkPartsAvailability(workOrderId, request.technicianId());
        }

        // 6. Apply state transition
        WorkOrderStatus newStatus = WorkOrderStatus.valueOf(descriptor.toState().name());
        if (request.event() == WorkOrderEvent.ASSIGN && request.technicianId() != null) {
            workOrder.assignTechnician(request.technicianId());
        } else {
            workOrder.applyStateTransition(newStatus);
        }
        WorkOrderState toState = descriptor.toState();

        // 7. Persist and flush to surface any concurrent-modification conflict within this transaction
        workOrderRepository.save(workOrder);
        try {
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("transition_concurrent_conflict work_order_id={} actor={}", workOrderId, scope.userId());
            throw new WorkOrderVersionConflictException("Concurrent modification detected; retry with latest version", e);
        }

        // 7.5 Persist assignment audit row with warning data (ASSIGN only)
        if (request.event() == WorkOrderEvent.ASSIGN && request.technicianId() != null) {
            persistAssignmentAudit(workOrderId, request, assignmentWarnings);
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

        // 9. Finalise SLA breach overrun when work order reaches a terminal state
        if (toState == WorkOrderState.CLOSED || toState == WorkOrderState.CANCELLED) {
            slaBreachService.finalise(workOrderId, occurredAt);
        }

        Set<WorkOrderEvent> legalNextEvents = transitionService.legalEventsFrom(toState);

        log.info("transition_applied work_order_id={} from_state={} event={} to_state={} version={} actor={}",
                workOrderId, fromState, request.event(), toState, workOrder.getVersion(), scope.userId());

        return TransitionResponse.ofWithWarnings(workOrder.getId(), fromState, toState,
                workOrder.getVersion(), legalNextEvents, occurredAt, assignmentWarnings);
    }

    private void applyHoldIntervalChanges(TransitionRequest request,
                                           UUID workOrderId,
                                           WorkOrderState fromState,
                                           UUID actorId,
                                           Instant occurredAt,
                                           WorkOrder workOrder,
                                           Set<WorkOrderEvent> legalEvents) {
        if (request.event() == WorkOrderEvent.HOLD) {
            WorkOrderHold hold = new WorkOrderHold(
                    workOrderId, request.holdReasonCode(), request.reason(), occurredAt, actorId);
            workOrderHoldRepository.save(hold);

            // Write SLA clock pause row if this hold reason suspends the SLA clock
            HoldReason reason = holdReasonService.findByCode(request.holdReasonCode());
            if (reason != null && reason.isPausesSlaClock()) {
                slaPolicyService.openPause(workOrderId, request.holdReasonCode(), occurredAt);
            }

        } else if (request.event() == WorkOrderEvent.RESUME) {
            WorkOrderHold openHold = workOrderHoldRepository
                    .findByWorkOrderIdAndEndedAtIsNull(workOrderId)
                    .orElseThrow(() -> new IllegalWorkOrderTransitionException(
                            fromState, WorkOrderEvent.RESUME, legalEvents));
            openHold.close(occurredAt, actorId);
            workOrderHoldRepository.save(openHold);
            long elapsed = Duration.between(openHold.getStartedAt(), occurredAt).toMinutes();
            workOrder.incrementCumulativeHoldMinutes((int) elapsed);

            // Close any open SLA clock pause
            slaPolicyService.closePause(workOrderId, occurredAt);

        } else if (fromState == WorkOrderState.ON_HOLD) {
            // Dangling-hold cleanup for any other event from ON_HOLD (e.g., CANCEL)
            workOrderHoldRepository.findByWorkOrderIdAndEndedAtIsNull(workOrderId)
                    .ifPresent(h -> {
                        h.close(occurredAt, actorId);
                        workOrderHoldRepository.save(h);
                        long elapsed = Duration.between(h.getStartedAt(), occurredAt).toMinutes();
                        workOrder.incrementCumulativeHoldMinutes((int) elapsed);
                    });
            slaPolicyService.closePause(workOrderId, occurredAt);
        }
    }

    private void runGuards(List<String> guardIds,
                            WorkOrderState fromState,
                            WorkOrderEvent event,
                            TransitionRequest request,
                            UUID workOrderId,
                            UUID actorId,
                            Instant transitionInstant) {
        if (guardIds.isEmpty()) return;

        GuardContext ctx = new GuardContext(
                workOrderId,
                request.technicianId(),
                request.holdReasonCode(),
                transitionInstant);

        for (String guardId : guardIds) {
            TransitionGuard guard = guards.stream()
                    .filter(g -> guardId.equals(g.guardId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessGuardException("Guard not configured: " + guardId));

            GuardResult result;
            try {
                result = guard.evaluate(fromState, event, ctx);
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
                throw new BusinessGuardException(refused.code(), refused.message());
            }
        }
    }

    private List<AssignmentWarning> checkPartsAvailability(UUID workOrderId, UUID technicianId) {
        try {
            // Load required parts for work order
            Map<UUID, Integer> requiredParts = new HashMap<>();
            namedJdbc.query(
                    "SELECT part_id::text, quantity_required FROM work_order_required_part " +
                    "WHERE work_order_id = :woId::uuid",
                    new MapSqlParameterSource("woId", workOrderId.toString()),
                    rs -> requiredParts.put(UUID.fromString(rs.getString(1)), rs.getInt(2)));

            if (requiredParts.isEmpty()) return List.of();

            // Load technician vehicle location
            Set<UUID> vehicleLocationIds = new HashSet<>();
            namedJdbc.query(
                    "SELECT id::text FROM stock_location WHERE technician_id = :techId::uuid " +
                    "AND location_type = 'VEHICLE'",
                    new MapSqlParameterSource("techId", technicianId.toString()),
                    rs -> vehicleLocationIds.add(UUID.fromString(rs.getString(1))));

            // Load warehouse locations
            Set<UUID> warehouseLocationIds = new HashSet<>();
            namedJdbc.query(
                    "SELECT id::text FROM stock_location WHERE location_type = 'WAREHOUSE'",
                    new MapSqlParameterSource(),
                    rs -> warehouseLocationIds.add(UUID.fromString(rs.getString(1))));

            PartsAvailabilityResult result = stockQueryService.queryAvailability(
                    new PartsAvailabilityQuery(requiredParts, vehicleLocationIds, warehouseLocationIds));

            List<AssignmentWarning> warnings = new ArrayList<>();
            for (UUID locId : vehicleLocationIds) {
                CandidateAvailability av = result.byLocationId().get(locId);
                if (av == null) continue;
                if (av.status() == AvailabilityStatus.FULLY_STOCKED) continue;
                String code = switch (av.status()) {
                    case UNAVAILABLE       -> AssignmentWarning.CODE_UNAVAILABLE;
                    case PARTIALLY_STOCKED -> AssignmentWarning.CODE_PARTIALLY_STOCKED;
                    case COLLECTABLE       -> AssignmentWarning.CODE_COLLECTABLE;
                    default                -> null;
                };
                if (code == null) continue;
                String message = switch (av.status()) {
                    case UNAVAILABLE       -> "One or more required parts are unavailable on vehicle or at warehouses";
                    case PARTIALLY_STOCKED -> "Some required parts are only partially available";
                    case COLLECTABLE       -> "Required parts are not on vehicle but can be collected from a warehouse";
                    default                -> "Parts availability warning";
                };
                warnings.add(new AssignmentWarning(code, message, av.shortfalls()));
            }
            return warnings;
        } catch (Exception e) {
            log.warn("transition_parts_check_failed workOrderId={} technicianId={} reason={}",
                    workOrderId, technicianId, e.getMessage());
            return List.of();
        }
    }

    private void persistAssignmentAudit(UUID workOrderId, TransitionRequest request,
                                         List<AssignmentWarning> warnings) {
        try {
            Assignment assignment = new Assignment(workOrderId, request.technicianId());
            if (!warnings.isEmpty()) {
                AssignmentWarning first = warnings.get(0);
                String shortfallJson = null;
                try {
                    shortfallJson = objectMapper.writeValueAsString(first.shortfalls());
                } catch (JsonProcessingException e) {
                    log.warn("assignment_audit_json_failed reason={}", e.getMessage());
                }
                assignment.applyWarning(
                        first.code(),
                        shortfallJson,
                        request.acknowledgeWarnings(),
                        request.warningAcknowledgementReason());
            }
            assignmentRepository.save(assignment);
        } catch (Exception e) {
            log.warn("assignment_audit_persist_failed workOrderId={} reason={}", workOrderId, e.getMessage());
        }
    }

    /**
     * Publishes a ReplenishmentNeeded outbox event in the same transaction as the hold.
     *
     * <p>Loads required parts from work_order_required_part and emits one event per part.
     * Failure is logged but never propagated — the hold transition must not be rolled back
     * due to a notification failure (AC7: delivery failure never blocks the hold).
     */
    private void emitReplenishmentNeeded(UUID workOrderId, UUID actorId, Instant occurredAt) {
        try {
            List<Map<String, Object>> requiredParts = namedJdbc.queryForList(
                    "SELECT rp.part_id::text AS part_id, rp.quantity_required, " +
                    "       p.part_number " +
                    "FROM work_order_required_part rp " +
                    "JOIN part p ON p.id = rp.part_id " +
                    "WHERE rp.work_order_id = :woId::uuid",
                    new MapSqlParameterSource("woId", workOrderId.toString()));

            if (requiredParts.isEmpty()) {
                log.debug("awaiting_parts_hold_no_required_parts work_order_id={}", workOrderId);
                return;
            }

            for (Map<String, Object> row : requiredParts) {
                UUID   partId     = UUID.fromString((String) row.get("part_id"));
                int    qty        = ((Number) row.get("quantity_required")).intValue();
                String partNumber = (String) row.get("part_number");

                ReplenishmentNeededPayload payload = new ReplenishmentNeededPayload(
                        partId, partNumber, null /* vehicle location unknown at hold time */,
                        qty, workOrderId, "ReplenishmentNeeded");

                eventPublisher.publish(new DomainEvent(
                        UuidV7.generate(),
                        "ReplenishmentNeeded",
                        "WORK_ORDER",
                        workOrderId,
                        occurredAt,
                        MDC.get("traceId"),
                        actorId,
                        payload));
            }
        } catch (Exception ex) {
            log.warn("awaiting_parts_replenishment_signal_failed work_order_id={} reason={}",
                    workOrderId, ex.getMessage(), ex);
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
