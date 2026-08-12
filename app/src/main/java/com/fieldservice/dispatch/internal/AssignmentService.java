package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.snapshot.RecommendationSnapshotRepository;
import com.fieldservice.dispatch.web.AssignmentRequest;
import com.fieldservice.dispatch.web.AssignmentResponse;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.domain.Assignment;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.lifecycle.TransitionDescriptor;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionService;
import com.fieldservice.workorder.lifecycle.WorkOrderVersionConflictException;
import com.fieldservice.workorder.repository.AssignmentRepository;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.web.AssignmentWarning;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates a work order assignment inside a single transaction boundary.
 *
 * <p>Order of operations (all within one transaction):
 * <ol>
 *   <li>Load and scope-check the work order.</li>
 *   <li>Validate the ASSIGN transition is legal and the actor is authorised.</li>
 *   <li>Run the hard certification guard (fail-closed; cannot be bypassed).</li>
 *   <li>Resolve recommendation rank and determine override requirement.</li>
 *   <li>Apply {@code WorkOrder.assignTechnician()} and persist.</li>
 *   <li>Persist the {@link Assignment} row with full audit fields.</li>
 *   <li>Flush to surface optimistic-lock conflicts within the transaction.</li>
 *   <li>Publish the {@code TECHNICIAN_ASSIGNED} outbox event.</li>
 * </ol>
 *
 * <p>Envers writes both the work order and assignment revisions automatically on commit.
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    static final int  OVERRIDE_RANK_THRESHOLD = 3;
    static final long STALENESS_DAYS          = 7L;

    private final ScopedQueryExecutor             scopedQueryExecutor;
    private final WorkOrderRepository             workOrderRepository;
    private final AssignmentRepository            assignmentRepository;
    private final WorkOrderTransitionService      transitionService;
    private final AssignmentGuard                 assignmentGuard;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final DomainEventPublisher            eventPublisher;
    private final EntityManager                   entityManager;
    private final Counter                         assignmentCounterNoOverride;
    private final Counter                         assignmentCounterOverride;
    private final DistributionSummary             rankSummary;

    public AssignmentService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            AssignmentRepository assignmentRepository,
            WorkOrderTransitionService transitionService,
            AssignmentGuard assignmentGuard,
            RecommendationSnapshotRepository snapshotRepository,
            DomainEventPublisher eventPublisher,
            EntityManager entityManager,
            MeterRegistry meterRegistry) {

        this.scopedQueryExecutor  = scopedQueryExecutor;
        this.workOrderRepository  = workOrderRepository;
        this.assignmentRepository = assignmentRepository;
        this.transitionService    = transitionService;
        this.assignmentGuard      = assignmentGuard;
        this.snapshotRepository   = snapshotRepository;
        this.eventPublisher       = eventPublisher;
        this.entityManager        = entityManager;

        this.assignmentCounterNoOverride = Counter.builder("dispatch.assignment.count")
                .tag("override", "false")
                .description("Number of work order assignments")
                .register(meterRegistry);
        this.assignmentCounterOverride = Counter.builder("dispatch.assignment.count")
                .tag("override", "true")
                .description("Number of work order assignments with override")
                .register(meterRegistry);
        this.rankSummary = DistributionSummary.builder("dispatch.assignment.rank")
                .description("Recommendation rank of accepted technician at assignment time")
                .register(meterRegistry);
    }

    /**
     * Assigns a technician to a work order.
     *
     * @param workOrderId  target work order
     * @param request      assignment request
     * @param scope        access scope of the acting dispatcher
     * @param assignedAt   assignment timestamp
     * @return the created assignment response
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    @Transactional
    public AssignmentResponse assign(UUID workOrderId,
                                     AssignmentRequest request,
                                     AccessScope scope,
                                     Instant assignedAt) {

        // 1. Load work order — scoped read; out-of-scope or missing id → 403
        WorkOrder workOrder = scopedQueryExecutor
                .findById(workOrderRepository, workOrderId, scope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "work_order", "Resource not found or outside caller scope"));

        // 2. Validate the ASSIGN transition is legal
        WorkOrderState fromState = WorkOrderState.valueOf(workOrder.getState().name());
        Set<WorkOrderEvent> legalEvents = transitionService.legalEventsFrom(fromState);
        TransitionDescriptor descriptor = transitionService
                .resolve(fromState, WorkOrderEvent.ASSIGN)
                .orElseThrow(() -> new IllegalWorkOrderTransitionException(
                        fromState, WorkOrderEvent.ASSIGN, legalEvents));

        // 3. Authorisation check from the transition table
        boolean authorized = descriptor.requiredRoles().stream()
                .anyMatch(scope.roles()::contains);
        if (!authorized) {
            throw new ForbiddenException(
                    "Role not permitted for ASSIGN from state " + fromState);
        }

        // 4. Hard certification guard — fail-closed, cannot be bypassed
        assignmentGuard.guard(workOrder, request.technicianId());

        // 5. Override determination
        Integer    rank            = null;
        BigDecimal score           = null;
        boolean    snapshotStale   = false;
        boolean    requiresOverride;

        if (request.recommendationSnapshotId() != null) {
            List<Map<String, Object>> snapshotHeader =
                    snapshotRepository.findById(request.recommendationSnapshotId());

            if (snapshotHeader.isEmpty()) {
                throw new AssignmentValidationException(
                        "recommendationSnapshotId",
                        "SNAPSHOT_NOT_FOUND",
                        "Recommendation snapshot " + request.recommendationSnapshotId() + " not found.");
            }

            // Snapshot must belong to this work order
            Object headerWoId = snapshotHeader.get(0).get("work_order_id");
            UUID snapshotWorkOrderId = headerWoId instanceof UUID u ? u
                    : UUID.fromString(String.valueOf(headerWoId));
            if (!workOrderId.equals(snapshotWorkOrderId)) {
                throw new AssignmentValidationException(
                        "recommendationSnapshotId",
                        "SNAPSHOT_WORK_ORDER_MISMATCH",
                        "Snapshot belongs to a different work order.");
            }

            // Staleness check
            Object generatedAtRaw = snapshotHeader.get(0).get("generated_at");
            if (generatedAtRaw instanceof java.sql.Timestamp ts) {
                Instant generatedAt = ts.toInstant();
                if (generatedAt.plus(Duration.ofDays(STALENESS_DAYS)).isBefore(assignedAt)) {
                    snapshotStale = true;
                }
            }

            // Look up the chosen technician's rank and score
            List<Map<String, Object>> candidates =
                    snapshotRepository.findCandidatesBySnapshotId(request.recommendationSnapshotId());
            for (Map<String, Object> row : candidates) {
                Object rowTechId = row.get("technician_id");
                UUID candidateTechId = rowTechId instanceof UUID u ? u
                        : UUID.fromString(String.valueOf(rowTechId));
                if (request.technicianId().equals(candidateTechId)) {
                    rank  = ((Number) row.get("rank")).intValue();
                    score = row.get("score") instanceof BigDecimal bd ? bd
                            : new BigDecimal(String.valueOf(row.get("score")));
                    break;
                }
            }

            requiresOverride = (rank == null) || (rank > OVERRIDE_RANK_THRESHOLD);
        } else {
            requiresOverride = true;
        }

        // 6. Validate override reason is present when required
        boolean overrideReasonBlank = request.overrideReason() == null
                || request.overrideReason().isBlank();
        if (requiresOverride && overrideReasonBlank) {
            throw new AssignmentValidationException(
                    "overrideReason",
                    "OVERRIDE_REASON_REQUIRED",
                    "overrideReason is required when the technician is not in the snapshot top-3 "
                            + "or no snapshot was supplied.");
        }

        // 7. Apply assignment to work order (sets state=ASSIGNED and assignedTechnicianId)
        workOrder.assignTechnician(request.technicianId());
        workOrderRepository.save(workOrder);

        // 8. Persist assignment audit row
        Assignment assignment = new Assignment(workOrderId, request.technicianId());
        assignment.setAssignedBy(scope.userId());
        assignment.setRecommendationSnapshotId(request.recommendationSnapshotId());
        assignment.setRecommendationRank(rank);
        assignment.setRecommendationScore(score);
        assignment.setOverrideReason(requiresOverride && !overrideReasonBlank
                ? request.overrideReason() : null);
        assignment.setSnapshotStale(snapshotStale);
        assignmentRepository.save(assignment);

        // 9. Flush — surfaces concurrent modification within this transaction
        try {
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("assignment_concurrent_conflict workOrderId={} actor={}", workOrderId, scope.userId());
            throw new WorkOrderVersionConflictException(
                    "Concurrent assignment detected; retry after the latest version.", e);
        }

        // 10. Publish outbox event (same transaction; rolls back on failure)
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "TECHNICIAN_ASSIGNED",
                "WORK_ORDER",
                workOrderId,
                assignedAt,
                MDC.get("traceId"),
                scope.userId(),
                new TechnicianAssignedPayload(
                        workOrderId, request.technicianId(), assignment.getId(),
                        rank, score, requiresOverride)));

        // 11. Micrometer
        boolean overrideRecorded = requiresOverride && !overrideReasonBlank;
        if (overrideRecorded) {
            assignmentCounterOverride.increment();
        } else {
            assignmentCounterNoOverride.increment();
        }
        if (rank != null) {
            rankSummary.record(rank);
        }

        log.info("assignment_applied workOrderId={} technicianId={} rank={} override={} actor={}",
                workOrderId, request.technicianId(), rank, overrideRecorded, scope.userId());

        String partsWarningCode = assignment.getPartsWarningCode();
        AssignmentWarning partsWarning = partsWarningCode != null
                ? new AssignmentWarning(partsWarningCode, null, List.of()) : null;

        return new AssignmentResponse(
                new AssignmentResponse.AssignmentData(
                        assignment.getId(),
                        workOrderId,
                        request.technicianId(),
                        "ASSIGNED",
                        assignment.getAssignedAt(),
                        rank,
                        score,
                        overrideRecorded,
                        partsWarning),
                new AssignmentResponse.Meta(MDC.get("traceId")));
    }
}
