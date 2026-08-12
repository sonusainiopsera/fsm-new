package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.web.ReassignmentRequest;
import com.fieldservice.dispatch.web.ReassignmentResponse;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.domain.Assignment;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.lifecycle.WorkOrderVersionConflictException;
import com.fieldservice.workorder.repository.AssignmentRepository;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates work order reassignment in a single atomic transaction.
 *
 * <p>Order of operations:
 * <ol>
 *   <li>Load and scope-check the work order.</li>
 *   <li>Validate state eligibility (ASSIGNED, EN_ROUTE, ON_HOLD, IN_PROGRESS).</li>
 *   <li>Load the active assignment and check for same-technician no-op.</li>
 *   <li>Validate reassignment reason (mandatory controlled enum).</li>
 *   <li>Run the hard certification guard (identical to initial assignment).</li>
 *   <li>Run the appointment guard for confirmed future windows.</li>
 *   <li>Supersede the active assignment (end_at + superseded_by).</li>
 *   <li>Persist the new assignment row.</li>
 *   <li>Update work order assigned technician (state unchanged).</li>
 *   <li>Flush to surface optimistic-lock conflicts.</li>
 *   <li>Publish TECHNICIAN_ASSIGNMENT_REVOKED and TECHNICIAN_ASSIGNED outbox events.</li>
 * </ol>
 */
@Service
public class ReassignmentService {

    private static final Logger log = LoggerFactory.getLogger(ReassignmentService.class);

    static final Set<WorkOrderStatus> PERMITTED_STATES = EnumSet.of(
            WorkOrderStatus.ASSIGNED,
            WorkOrderStatus.EN_ROUTE,
            WorkOrderStatus.ON_HOLD,
            WorkOrderStatus.IN_PROGRESS
    );

    private final ScopedQueryExecutor  scopedQueryExecutor;
    private final WorkOrderRepository  workOrderRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentGuard      assignmentGuard;
    private final AppointmentGuard     appointmentGuard;
    private final DomainEventPublisher eventPublisher;
    private final EntityManager        entityManager;
    private final Map<ReassignmentReason, Counter> reasonCounters;
    private final Counter apptAckCounter;

    public ReassignmentService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            AssignmentRepository assignmentRepository,
            AssignmentGuard assignmentGuard,
            AppointmentGuard appointmentGuard,
            DomainEventPublisher eventPublisher,
            EntityManager entityManager,
            MeterRegistry meterRegistry) {

        this.scopedQueryExecutor  = scopedQueryExecutor;
        this.workOrderRepository  = workOrderRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignmentGuard      = assignmentGuard;
        this.appointmentGuard     = appointmentGuard;
        this.eventPublisher       = eventPublisher;
        this.entityManager        = entityManager;

        this.reasonCounters = new EnumMap<>(ReassignmentReason.class);
        for (ReassignmentReason reason : ReassignmentReason.values()) {
            reasonCounters.put(reason, Counter.builder("dispatch.reassignment.count")
                    .tag("reason", reason.name())
                    .tag("appointment_ack", "false")
                    .description("Reassignment count by reason")
                    .register(meterRegistry));
        }
        this.apptAckCounter = Counter.builder("dispatch.reassignment.count")
                .tag("reason", "ANY")
                .tag("appointment_ack", "true")
                .description("Reassignment count where appointment acknowledgement was required")
                .register(meterRegistry);
    }

    /**
     * Reassigns a work order from its current technician to a new one.
     *
     * @param workOrderId    target work order
     * @param request        reassignment request
     * @param scope          access scope of the acting dispatcher
     * @param reassignedAt   timestamp for the operation
     * @return the reassignment response
     */
    @Transactional
    public ReassignmentResponse reassign(UUID workOrderId,
                                         ReassignmentRequest request,
                                         AccessScope scope,
                                         Instant reassignedAt) {

        // 1. Load work order — scoped read; out-of-scope or missing → 403
        WorkOrder workOrder = scopedQueryExecutor
                .findById(workOrderRepository, workOrderId, scope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "work_order", "Resource not found or outside caller scope"));

        // 2. State eligibility check
        WorkOrderStatus currentStatus = workOrder.getState();
        if (!PERMITTED_STATES.contains(currentStatus)) {
            throw new ReassignmentNotPermittedException(currentStatus);
        }

        // 3. Load active assignment
        Assignment activeAssignment = assignmentRepository
                .findByWorkOrderIdAndEndAtIsNull(workOrderId)
                .orElseThrow(() -> new AssignmentValidationException(
                        "workOrderId",
                        "NO_ACTIVE_ASSIGNMENT",
                        "No active assignment found for work order " + workOrderId + "."));

        // 4. Same-technician no-op check
        if (request.technicianId().equals(activeAssignment.getTechnicianId())) {
            throw new AssignmentValidationException(
                    "technicianId",
                    "SAME_TECHNICIAN_NO_OP",
                    "The work order is already assigned to technician " + request.technicianId()
                            + ". Reassigning to the same technician is not permitted.");
        }

        // 5. Validate reassignment reason
        ReassignmentReason parsedReason = parseReason(request.reassignmentReason());

        // 6. Hard certification guard — identical to initial assignment; cannot be bypassed
        assignmentGuard.guard(workOrder, request.technicianId());

        // 7. Appointment guard — requires explicit acknowledgement for future confirmed windows
        appointmentGuard.guard(workOrder, request.appointmentImpactAcknowledgement());

        // 8. Build new assignment
        Assignment newAssignment = new Assignment(workOrderId, request.technicianId());
        newAssignment.setAssignedBy(scope.userId());
        newAssignment.setReassignmentReason(parsedReason.name());
        newAssignment.setReasonNotes(request.reasonNotes());
        newAssignment.setRecommendationSnapshotId(request.recommendationSnapshotId());
        if (request.overrideReason() != null && !request.overrideReason().isBlank()) {
            newAssignment.setOverrideReason(request.overrideReason());
        }
        boolean appointmentImpactRecorded = request.appointmentImpactAcknowledgement() != null
                && !request.appointmentImpactAcknowledgement().isBlank();
        if (appointmentImpactRecorded) {
            newAssignment.setAppointmentImpactReason(request.appointmentImpactAcknowledgement());
        }

        // 9. Supersede the active assignment
        UUID supersededId = activeAssignment.getId();
        activeAssignment.setEndAt(reassignedAt);
        activeAssignment.setSupersededBy(newAssignment.getId());
        assignmentRepository.save(activeAssignment);
        assignmentRepository.save(newAssignment);

        // 10. Update assigned technician on work order (state unchanged)
        workOrder.reassignTechnician(request.technicianId());
        workOrderRepository.save(workOrder);

        // 11. Flush — surfaces concurrent modification within this transaction
        try {
            entityManager.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("reassignment_concurrent_conflict workOrderId={} actor={}", workOrderId, scope.userId());
            throw new WorkOrderVersionConflictException(
                    "Concurrent reassignment detected; retry after the latest version.", e);
        }

        // 12. Publish outbox events (same transaction; rolls back on failure)
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "TECHNICIAN_ASSIGNMENT_REVOKED",
                "WORK_ORDER",
                workOrderId,
                reassignedAt,
                MDC.get("traceId"),
                scope.userId(),
                new TechnicianRevokedPayload(
                        workOrderId,
                        activeAssignment.getTechnicianId(),
                        supersededId,
                        newAssignment.getId(),
                        parsedReason.name())));

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "TECHNICIAN_ASSIGNED",
                "WORK_ORDER",
                workOrderId,
                reassignedAt,
                MDC.get("traceId"),
                scope.userId(),
                new TechnicianAssignedPayload(
                        workOrderId,
                        request.technicianId(),
                        newAssignment.getId(),
                        null, null, false)));

        // 13. Micrometer
        reasonCounters.get(parsedReason).increment();
        if (appointmentImpactRecorded) {
            apptAckCounter.increment();
        }

        log.info("reassignment_applied workOrderId={} from={} to={} reason={} apptAck={} actor={}",
                workOrderId, activeAssignment.getTechnicianId(), request.technicianId(),
                parsedReason, appointmentImpactRecorded, scope.userId());

        return new ReassignmentResponse(
                new ReassignmentResponse.ReassignmentData(
                        newAssignment.getId(),
                        supersededId,
                        workOrderId,
                        request.technicianId(),
                        currentStatus.name(),
                        parsedReason.name(),
                        appointmentImpactRecorded,
                        newAssignment.getAssignedAt()),
                new ReassignmentResponse.Meta(MDC.get("traceId")));
    }

    /**
     * Returns the complete ordered assignment history for a work order.
     */
    public java.util.List<Assignment> getHistory(UUID workOrderId) {
        return assignmentRepository.findByWorkOrderIdOrderByCreatedAtAsc(workOrderId);
    }

    private static ReassignmentReason parseReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AssignmentValidationException(
                    "reassignmentReason",
                    "REASSIGNMENT_REASON_REQUIRED",
                    "reassignmentReason is required and must be one of: "
                            + java.util.Arrays.toString(ReassignmentReason.values()));
        }
        try {
            return ReassignmentReason.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AssignmentValidationException(
                    "reassignmentReason",
                    "INVALID_REASSIGNMENT_REASON",
                    "Unknown reassignmentReason '" + raw + "'. Must be one of: "
                            + java.util.Arrays.toString(ReassignmentReason.values()));
        }
    }
}
