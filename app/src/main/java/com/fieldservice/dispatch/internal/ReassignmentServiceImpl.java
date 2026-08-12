package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.AssignmentService.CertificationGuardException;
import com.fieldservice.dispatch.api.AssignmentService.OverrideReasonRequiredException;
import com.fieldservice.dispatch.api.ReassignmentReason;
import com.fieldservice.dispatch.api.ReassignmentService;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshot;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotCandidate;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotCandidateRepository;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotRepository;
import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.assignment.AssignmentRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderCompetency;
import com.fieldservice.domain.workorder.WorkOrderCompetencyRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.outbox.payload.TechnicianAssignedPayload;
import com.fieldservice.outbox.payload.TechnicianUnassignedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements the dispatcher reassignment flow (WO-139).
 *
 * <h3>Transaction atomicity</h3>
 * All writes — supersede old assignment, persist new assignment, update work order's
 * assigned_technician_id, Envers revisions, TechnicianUnassigned + TechnicianAssigned
 * outbox events — share one {@link Transactional} boundary. A thrown exception rolls
 * back all writes.
 *
 * <h3>Supersede semantics</h3>
 * The old assignment is closed by setting {@code end_at = now} and {@code is_current = false}.
 * The new assignment is created with {@code end_at = null} and {@code is_current = true}.
 * After save, {@code superseded_by} on the old assignment is set to the new assignment's ID.
 * The {@code idx_assignment_active_unique} partial index (on {@code end_at IS NULL}) enforces
 * the at-most-one-active-assignment invariant at the database level.
 */
@Service
class ReassignmentServiceImpl implements ReassignmentService {

    private static final Logger log = LoggerFactory.getLogger(ReassignmentServiceImpl.class);

    private static final Set<WorkOrderState> ELIGIBLE_STATES = EnumSet.of(
            WorkOrderState.ASSIGNED,
            WorkOrderState.EN_ROUTE,
            WorkOrderState.ON_HOLD,
            WorkOrderState.IN_PROGRESS
    );

    private static final int OVERRIDE_RANK_THRESHOLD = 3;

    private final EntityManager entityManager;
    private final AssignmentRepository assignmentRepository;
    private final WorkOrderCompetencyRepository competencyRepository;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final RecommendationSnapshotCandidateRepository snapshotCandidateRepository;
    private final DomainEventPublisher eventPublisher;
    private final AssignmentGuard certificationGuard;
    private final AppointmentGuard appointmentGuard;
    private final MeterRegistry meterRegistry;

    ReassignmentServiceImpl(
            EntityManager entityManager,
            AssignmentRepository assignmentRepository,
            WorkOrderCompetencyRepository competencyRepository,
            RecommendationSnapshotRepository snapshotRepository,
            RecommendationSnapshotCandidateRepository snapshotCandidateRepository,
            DomainEventPublisher eventPublisher,
            AssignmentGuard certificationGuard,
            AppointmentGuard appointmentGuard,
            MeterRegistry meterRegistry) {
        this.entityManager                = entityManager;
        this.assignmentRepository         = assignmentRepository;
        this.competencyRepository         = competencyRepository;
        this.snapshotRepository           = snapshotRepository;
        this.snapshotCandidateRepository  = snapshotCandidateRepository;
        this.eventPublisher               = eventPublisher;
        this.certificationGuard           = certificationGuard;
        this.appointmentGuard             = appointmentGuard;
        this.meterRegistry                = meterRegistry;
    }

    @Override
    @Transactional
    public ReassignmentResult reassign(
            UUID workOrderId,
            UUID incomingTechnicianId,
            UUID reassignedBy,
            ReassignmentReason reassignmentReason,
            String reasonNotes,
            UUID recommendationSnapshotId,
            String overrideReason,
            String appointmentImpactAcknowledgement,
            int expectedVersion) {

        // ── 1. Load work order ─────────────────────────────────────────────────
        WorkOrder workOrder = entityManager.find(WorkOrder.class, workOrderId);
        if (workOrder == null) {
            throw new NotFoundException("work-order", workOrderId);
        }

        // ── 2. State eligibility check ─────────────────────────────────────────
        if (!ELIGIBLE_STATES.contains(workOrder.getState())) {
            throw new ReassignmentStateException(workOrder.getState());
        }

        // ── 3. Same-technician no-op check ─────────────────────────────────────
        UUID currentTechnicianId = workOrder.getAssignedTechnicianId();
        if (incomingTechnicianId.equals(currentTechnicianId)) {
            throw new SameAssigneeException();
        }

        // ── 4. Certification hard guard (never fail-open) ──────────────────────
        WorkOrderRequirements requirements = buildRequirements(workOrder);
        certificationGuard.evaluate(incomingTechnicianId, requirements);

        // ── 5. Appointment-pinning guard ──────────────────────────────────────
        appointmentGuard.evaluate(workOrder, appointmentImpactAcknowledgement);

        // ── 6. Override determination from snapshot ────────────────────────────
        Integer rank  = null;
        Double  score = null;
        boolean snapshotStale = false;

        if (recommendationSnapshotId != null) {
            RecommendationSnapshot snapshot = snapshotRepository.findById(recommendationSnapshotId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Recommendation snapshot not found: " + recommendationSnapshotId));

            if (!workOrderId.equals(snapshot.getWorkOrderId())) {
                throw new IllegalArgumentException(
                        "Snapshot " + recommendationSnapshotId + " belongs to a different work order.");
            }

            snapshotStale = Duration.between(snapshot.getGeneratedAt(), Instant.now())
                    .compareTo(Duration.ofMinutes(120)) > 0;

            Optional<RecommendationSnapshotCandidate> candidate =
                    snapshotCandidateRepository.findBySnapshotIdOrderByRankAsc(recommendationSnapshotId)
                            .stream()
                            .filter(c -> incomingTechnicianId.equals(c.getTechnicianId()))
                            .findFirst();

            if (candidate.isPresent()) {
                rank  = candidate.get().getRank();
                score = candidate.get().getScore();
            }
        }

        boolean overrideRequired = rank == null || rank > OVERRIDE_RANK_THRESHOLD;
        if (overrideRequired && (overrideReason == null || overrideReason.isBlank())) {
            throw new OverrideReasonRequiredException(
                    rank == null
                    ? "Override reason is required: technician is absent from the recommendation snapshot."
                    : "Override reason is required: technician is ranked " + rank + " (top-"
                            + OVERRIDE_RANK_THRESHOLD + " threshold).");
        }

        // ── 7. Find and supersede the active assignment ────────────────────────
        Assignment activeAssignment = assignmentRepository.findActiveByWorkOrderId(workOrderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No active assignment found for work order " + workOrderId
                        + " (state=" + workOrder.getState() + "). Data integrity violation."));

        Instant now = Instant.now();
        activeAssignment.setEndAt(now);
        activeAssignment.setCurrent(false);
        activeAssignment.setUnassignedAt(now);
        assignmentRepository.save(activeAssignment); // flush end_at so unique index clears

        // ── 8. Create new assignment ───────────────────────────────────────────
        Assignment newAssignment = new Assignment();
        newAssignment.setWorkOrderId(workOrderId);
        newAssignment.setTechnicianId(incomingTechnicianId);
        newAssignment.setAssignedBy(reassignedBy);
        newAssignment.setRecommendationSnapshotId(recommendationSnapshotId);
        newAssignment.setRecommendationRank(rank);
        newAssignment.setRecommendationScore(score);
        newAssignment.setOverrideReason(overrideReason != null && !overrideReason.isBlank() ? overrideReason : null);
        newAssignment.setSnapshotStale(snapshotStale);
        newAssignment.setCurrent(true);
        newAssignment.setReassignmentReason(reassignmentReason.name());
        newAssignment.setReasonNotes(reasonNotes);
        boolean appointmentImpactRecorded = appointmentImpactAcknowledgement != null
                && !appointmentImpactAcknowledgement.isBlank();
        if (appointmentImpactRecorded) {
            newAssignment.setAppointmentImpactReason(appointmentImpactAcknowledgement);
        }
        newAssignment = assignmentRepository.save(newAssignment);

        // ── 9. Back-link: old assignment superseded_by = new assignment ID ─────
        activeAssignment.setSupersededBy(newAssignment.getId());
        assignmentRepository.save(activeAssignment);

        // ── 10. Update work order assigned technician ──────────────────────────
        workOrder.setAssignedTechnicianId(incomingTechnicianId);
        // Hibernate Envers tracks this via @Audited on WorkOrder — no explicit call needed.

        Instant reassignedAt = newAssignment.getAssignedAt() != null ? newAssignment.getAssignedAt() : now;
        UUID outgoingTechnicianId = currentTechnicianId;

        // ── 11. Publish outbox events (both in same transaction) ───────────────
        // Revoke event for the outgoing technician
        eventPublisher.publish(DomainEvent.of(
                TechnicianUnassignedPayload.EVENT_TYPE,
                TechnicianUnassignedPayload.AGGREGATE_TYPE,
                activeAssignment.getId(),
                reassignedAt,
                null,
                reassignedBy,
                Map.of(
                        "workOrderId",             workOrderId.toString(),
                        "supersededAssignmentId",  activeAssignment.getId().toString(),
                        "outgoingTechnicianId",    outgoingTechnicianId != null ? outgoingTechnicianId.toString() : "",
                        "reassignedBy",            reassignedBy.toString(),
                        "unassignedAt",            now.toString(),
                        "reassignmentReason",      reassignmentReason.name(),
                        "priority",                workOrder.getPriority() != null ? workOrder.getPriority().name() : "",
                        "reference",               workOrder.getReference() != null ? workOrder.getReference() : ""
                )));

        // Assignment event for the incoming technician
        eventPublisher.publish(DomainEvent.of(
                TechnicianAssignedPayload.EVENT_TYPE,
                TechnicianAssignedPayload.AGGREGATE_TYPE,
                newAssignment.getId(),
                reassignedAt,
                null,
                reassignedBy,
                Map.of(
                        "workOrderId",  workOrderId.toString(),
                        "assignmentId", newAssignment.getId().toString(),
                        "technicianId", incomingTechnicianId.toString(),
                        "assignedBy",   reassignedBy.toString(),
                        "assignedAt",   reassignedAt.toString(),
                        "priority",     workOrder.getPriority() != null ? workOrder.getPriority().name() : "",
                        "reference",    workOrder.getReference() != null ? workOrder.getReference() : ""
                )));

        // ── 12. Observability ──────────────────────────────────────────────────
        meterRegistry.counter("dispatch.reassignment.count",
                Tags.of(
                        "reason",        reassignmentReason.name(),
                        "appt_ack",      appointmentImpactRecorded ? "true" : "false",
                        "override",      (overrideReason != null && !overrideReason.isBlank()) ? "true" : "false"
                )).increment();

        log.info("dispatch.reassignment.created: actor={} workOrderId={} incomingTechId={} "
                 + "outgoingTechId={} reason={} apptAck={}",
                reassignedBy, workOrderId, incomingTechnicianId, outgoingTechnicianId,
                reassignmentReason, appointmentImpactRecorded);

        return new ReassignmentResult(
                newAssignment.getId(),
                activeAssignment.getId(),
                workOrderId,
                incomingTechnicianId,
                workOrder.getState(),
                reassignedAt,
                reassignmentReason.name(),
                appointmentImpactRecorded,
                overrideReason != null && !overrideReason.isBlank()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentHistoryEntry> getHistory(UUID workOrderId) {
        return assignmentRepository.findHistoryByWorkOrderId(workOrderId)
                .stream()
                .map(a -> new AssignmentHistoryEntry(
                        a.getId(),
                        a.getTechnicianId(),
                        a.getAssignedBy(),
                        a.getAssignedAt(),
                        a.getEndAt(),
                        a.getSuperscededBy(),
                        a.getReassignmentReason(),
                        a.getReasonNotes(),
                        a.getEndAt() == null
                ))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkOrderRequirements buildRequirements(WorkOrder wo) {
        Set<String> certs = competencyRepository.findByWorkOrderId(wo.getId()).stream()
                .map(WorkOrderCompetency::getCompetencyCode)
                .collect(Collectors.toSet());

        Double lat = null;
        Double lon = null;
        if (wo.getSite() != null) {
            if (wo.getSite().getLatitude()  != null) lat = wo.getSite().getLatitude().doubleValue();
            if (wo.getSite().getLongitude() != null) lon = wo.getSite().getLongitude().doubleValue();
        }

        return new WorkOrderRequirements(
                certs,
                wo.getScheduledWindowStart() != null ? wo.getScheduledWindowStart() : Instant.now(),
                wo.getScheduledWindowEnd()   != null ? wo.getScheduledWindowEnd()   : Instant.now().plusSeconds(3600),
                lat,
                lon,
                250.0
        );
    }
}
