package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.AssignmentService;
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
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.workorder.WorkOrderTransitionService;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements the dispatcher assignment flow (WO-138).
 *
 * <h3>Transaction atomicity</h3>
 * All five writes (assigned_technician_id, work-order state, Envers revisions for
 * both assignment and work-order, and outbox event) share one {@link Transactional}
 * boundary. A thrown exception rolls back all five.
 *
 * <h3>Certification hard guard sequencing</h3>
 * The {@link AssignmentGuard} is evaluated first — before any state mutation — using
 * {@link com.fieldservice.dispatch.api.EligibilityService}. When the guard passes, the
 * technician ID is set on the in-memory managed {@link WorkOrder} entity (not yet flushed).
 * The subsequent {@link WorkOrderTransitionService#applyEvent} call loads the same entity
 * from the JPA L1 identity map (same transaction = same persistence context), so the
 * {@code CertificationCurrencyGuard} inside the transition table finds the technician ID
 * and confirms certification currency — providing defense-in-depth without a redundant
 * database round trip.
 */
@Service
class AssignmentServiceImpl implements AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentServiceImpl.class);

    private static final int OVERRIDE_RANK_THRESHOLD = 3;

    private final EntityManager entityManager;
    private final AssignmentRepository assignmentRepository;
    private final WorkOrderCompetencyRepository competencyRepository;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final RecommendationSnapshotCandidateRepository snapshotCandidateRepository;
    private final WorkOrderTransitionService transitionService;
    private final DomainEventPublisher eventPublisher;
    private final AssignmentGuard guard;
    private final Duration snapshotStalenessWindow;
    private final Counter overrideCounter;
    private final Counter nonOverrideCounter;
    private final DistributionSummary rankSummary;

    AssignmentServiceImpl(
            EntityManager entityManager,
            AssignmentRepository assignmentRepository,
            WorkOrderCompetencyRepository competencyRepository,
            RecommendationSnapshotRepository snapshotRepository,
            RecommendationSnapshotCandidateRepository snapshotCandidateRepository,
            WorkOrderTransitionService transitionService,
            DomainEventPublisher eventPublisher,
            AssignmentGuard guard,
            MeterRegistry meterRegistry,
            @Value("${dispatch.assignment.snapshot-staleness-minutes:120}") int stalenessMinutes) {
        this.entityManager              = entityManager;
        this.assignmentRepository       = assignmentRepository;
        this.competencyRepository       = competencyRepository;
        this.snapshotRepository         = snapshotRepository;
        this.snapshotCandidateRepository = snapshotCandidateRepository;
        this.transitionService          = transitionService;
        this.eventPublisher             = eventPublisher;
        this.guard                      = guard;
        this.snapshotStalenessWindow    = Duration.ofMinutes(stalenessMinutes);
        this.overrideCounter    = meterRegistry.counter("dispatch.assignment.count", "override", "true");
        this.nonOverrideCounter = meterRegistry.counter("dispatch.assignment.count", "override", "false");
        this.rankSummary        = DistributionSummary.builder("dispatch.assignment.rank")
                .description("Rank of the accepted technician in the recommendation snapshot")
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public AssignmentResult assign(UUID workOrderId, UUID technicianId, UUID assignedBy,
                                   UUID recommendationSnapshotId, String overrideReason,
                                   int expectedVersion) {

        // Load work order into JPA L1 identity map
        WorkOrder workOrder = entityManager.find(WorkOrder.class, workOrderId);
        if (workOrder == null) {
            throw new NotFoundException("work-order", workOrderId);
        }

        // ── Hard guard: must run before any mutation ───────────────────────────
        // Evaluates active status, certification currency, availability, and reach.
        // EligibilityDataException propagates on data failure → 503 (never fail-open).
        WorkOrderRequirements requirements = buildRequirements(workOrder);
        guard.evaluate(technicianId, requirements);

        // ── Override determination (requires snapshot) ─────────────────────────
        Integer rank         = null;
        Double  score        = null;
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
                    .compareTo(snapshotStalenessWindow) > 0;

            Optional<RecommendationSnapshotCandidate> candidate =
                    snapshotCandidateRepository.findBySnapshotIdOrderByRankAsc(recommendationSnapshotId)
                            .stream()
                            .filter(c -> technicianId.equals(c.getTechnicianId()))
                            .findFirst();

            if (candidate.isPresent()) {
                rank  = candidate.get().getRank();
                score = candidate.get().getScore();
            }
        }

        boolean overrideRequired = rank == null || rank > OVERRIDE_RANK_THRESHOLD;
        if (overrideRequired && (overrideReason == null || overrideReason.isBlank())) {
            String msg = rank == null
                    ? "Override reason is required: technician is absent from the recommendation snapshot."
                    : "Override reason is required: technician is ranked " + rank
                    + " (top-" + OVERRIDE_RANK_THRESHOLD + " threshold).";
            throw new OverrideReasonRequiredException(msg);
        }

        // ── Set technician on managed entity (L1 cache) before transition ──────
        // applyEvent loads via EntityManager.find → same persistence context → same object.
        // CertificationCurrencyGuard inside the transition reads assignedTechnicianId here.
        workOrder.setAssignedTechnicianId(technicianId);

        // ── Transition: NEW → ASSIGNED + Envers revision + WorkOrderStateChanged outbox ─
        transitionService.applyEvent(workOrderId, WorkOrderEvent.ASSIGN);

        // ── Persist assignment row (Envers writes assignment_aud revision) ─────
        Assignment assignment = new Assignment();
        assignment.setWorkOrderId(workOrderId);
        assignment.setTechnicianId(technicianId);
        assignment.setAssignedBy(assignedBy);
        assignment.setRecommendationSnapshotId(recommendationSnapshotId);
        assignment.setRecommendationRank(rank);
        assignment.setRecommendationScore(score);
        assignment.setOverrideReason(overrideReason != null && !overrideReason.isBlank()
                ? overrideReason : null);
        assignment.setSnapshotStale(snapshotStale);
        assignment.setCurrent(true);
        assignment = assignmentRepository.save(assignment);

        Instant assignedAt = assignment.getAssignedAt() != null ? assignment.getAssignedAt() : Instant.now();

        // ── Publish technician-notification outbox event ───────────────────────
        eventPublisher.publish(DomainEvent.of(
                TechnicianAssignedPayload.EVENT_TYPE,
                TechnicianAssignedPayload.AGGREGATE_TYPE,
                assignment.getId(),
                assignedAt,
                null,
                assignedBy,
                Map.of(
                        "workOrderId",  workOrderId.toString(),
                        "assignmentId", assignment.getId().toString(),
                        "technicianId", technicianId.toString(),
                        "assignedBy",   assignedBy.toString(),
                        "assignedAt",   assignedAt.toString(),
                        "priority",     workOrder.getPriority() != null ? workOrder.getPriority().name() : "",
                        "reference",    workOrder.getReference() != null ? workOrder.getReference() : ""
                )));

        // ── Observability ───────────────────────────────────────────────────────
        boolean isOverride = overrideReason != null && !overrideReason.isBlank();
        if (isOverride) overrideCounter.increment();
        else            nonOverrideCounter.increment();
        if (rank != null) rankSummary.record(rank);

        log.info("dispatch.assignment.created: actor={} workOrderId={} technicianId={} rank={} override={}",
                assignedBy, workOrderId, technicianId, rank, isOverride);

        return new AssignmentResult(
                assignment.getId(),
                workOrderId,
                technicianId,
                WorkOrderState.ASSIGNED,
                assignedAt,
                rank,
                score,
                isOverride,
                snapshotStale,
                null
        );
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
