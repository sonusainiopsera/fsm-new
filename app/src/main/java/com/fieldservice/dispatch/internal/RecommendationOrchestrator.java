package com.fieldservice.dispatch.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.dispatch.scoring.ScoredCandidate;
import com.fieldservice.domain.workorder.WorkOrderCompetency;
import com.fieldservice.domain.workorder.WorkOrderCompetencyRepository;
import com.fieldservice.domain.inventory.WorkOrderRequiredPartRepository;
import com.fieldservice.inventory.api.CandidateAvailabilityResult;
import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;
import com.fieldservice.inventory.api.RequiredPartQuantity;
import com.fieldservice.inventory.api.StockQueryService;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringEngine;
import com.fieldservice.dispatch.scoring.ScoringWeights;
import com.fieldservice.dispatch.scoring.ScoringWeightsLoader;
import com.fieldservice.dispatch.scoring.TravelTimeEstimate;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshot;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotCandidate;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotCandidateRepository;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotRepository;
import com.fieldservice.dispatch.web.RecommendationCursorCodec;
import com.fieldservice.dispatch.web.dto.CandidateDto;
import com.fieldservice.dispatch.web.dto.ExclusionSummaryEntry;
import com.fieldservice.dispatch.web.dto.FactorDto;
import com.fieldservice.dispatch.web.dto.RecommendationResponse;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.geo.api.TravelCoordinate;
import com.fieldservice.geo.api.TravelMatrixEntry;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import com.fieldservice.platform.exception.BusinessGuardException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates technician recommendation generation for a single work order request.
 *
 * <p>Sequence per request:
 * <ol>
 *   <li>Assert work order is in an assignable state (NEW) — throws {@link BusinessGuardException} → 422.</li>
 *   <li>Build {@link WorkOrderRequirements} from the work order.</li>
 *   <li>Call {@link EligibilityService#evaluate(WorkOrderRequirements)} to filter all active technicians.</li>
 *   <li>Load technician scoring data (home coordinates, certifications, booked hours).</li>
 *   <li>Issue exactly one batched {@link TravelTimePort#estimateTravelTime} call for all eligible origins.</li>
 *   <li>Call {@link ScoringEngine#rank(List, ScoringWeights)} over the full eligible pool.</li>
 *   <li>Apply keyset cursor pagination and assemble DTOs.</li>
 *   <li>Persist an immutable {@link RecommendationSnapshot} with all candidate rows.</li>
 *   <li>Return the response envelope.</li>
 * </ol>
 */
@Service
public class RecommendationOrchestrator {

    private static final Set<WorkOrderState> ASSIGNABLE_STATES = Set.of(WorkOrderState.NEW);
    private static final int MAX_CANDIDATE_POOL = 200;

    private final EligibilityService eligibilityService;
    private final TravelTimePort travelTimePort;
    private final ScoringEngine scoringEngine;
    private final ScoringWeightsLoader weightsLoader;
    private final CandidateScoringDataLoader dataLoader;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final RecommendationSnapshotCandidateRepository candidateRepository;
    private final RecommendationCursorCodec cursorCodec;
    private final ObjectMapper objectMapper;
    private final WorkOrderRequiredPartRepository requiredPartRepository;
    private final StockQueryService stockQueryService;

    public RecommendationOrchestrator(
            EligibilityService eligibilityService,
            TravelTimePort travelTimePort,
            ScoringEngine scoringEngine,
            ScoringWeightsLoader weightsLoader,
            CandidateScoringDataLoader dataLoader,
            RecommendationSnapshotRepository snapshotRepository,
            RecommendationSnapshotCandidateRepository candidateRepository,
            RecommendationCursorCodec cursorCodec,
            ObjectMapper objectMapper,
            WorkOrderRequiredPartRepository requiredPartRepository,
            StockQueryService stockQueryService) {
        this.eligibilityService = eligibilityService;
        this.travelTimePort = travelTimePort;
        this.scoringEngine = scoringEngine;
        this.weightsLoader = weightsLoader;
        this.dataLoader = dataLoader;
        this.snapshotRepository = snapshotRepository;
        this.candidateRepository = candidateRepository;
        this.cursorCodec = cursorCodec;
        this.objectMapper = objectMapper;
        this.requiredPartRepository = requiredPartRepository;
        this.stockQueryService = stockQueryService;
    }

    @Transactional
    public RecommendationResponse recommend(WorkOrder workOrder, UUID actorId,
                                             String cursorToken, int pageSize) {
        assertAssignableState(workOrder);

        WorkOrderRequirements requirements = buildRequirements(workOrder);
        ScoringWeights weights = weightsLoader.load();

        EligibilityResult eligibility = eligibilityService.evaluate(requirements);

        List<UUID> eligibleIds = eligibility.eligibleTechnicianIds();
        boolean truncated = eligibleIds.size() > MAX_CANDIDATE_POOL;
        if (truncated) {
            eligibleIds = eligibleIds.subList(0, MAX_CANDIDATE_POOL);
        }
        int candidatePoolSize = eligibleIds.size();

        List<TechnicianScoringData> techData = dataLoader.load(eligibleIds);
        Map<UUID, TechnicianScoringData> techById = techData.stream()
                .collect(Collectors.toMap(TechnicianScoringData::technicianId, t -> t));

        // One batched travel-time call for all eligible origins
        TravelCoordinate destination = null;
        if (workOrder.getSite() != null
                && workOrder.getSite().getLatitude() != null
                && workOrder.getSite().getLongitude() != null) {
            destination = new TravelCoordinate(
                    workOrder.getSite().getLatitude().doubleValue(),
                    workOrder.getSite().getLongitude().doubleValue());
        }

        Map<UUID, TravelMatrixEntry> travelByTechId = new HashMap<>();
        boolean travelDegraded = false;

        if (destination != null && !techData.isEmpty()) {
            List<TravelCoordinate> origins = new ArrayList<>();
            List<UUID> originTechIds = new ArrayList<>();

            for (TechnicianScoringData td : techData) {
                if (td.homeLatitude() != null && td.homeLongitude() != null) {
                    origins.add(new TravelCoordinate(td.homeLatitude(), td.homeLongitude()));
                    originTechIds.add(td.technicianId());
                }
            }

            if (!origins.isEmpty()) {
                TravelMatrixResult travelResult = travelTimePort.estimateTravelTime(origins, destination);
                travelDegraded = travelResult.anyDegraded();

                List<TravelMatrixEntry> entries = travelResult.entries();
                for (int i = 0; i < originTechIds.size() && i < entries.size(); i++) {
                    travelByTechId.put(originTechIds.get(i), entries.get(i));
                }
            }
        }

        // Parts availability — one batch call for all candidates
        List<RequiredPartQuantity> requiredParts = requiredPartRepository
                .findByWorkOrderId(workOrder.getId()).stream()
                .map(rp -> new RequiredPartQuantity(rp.getPartId(), rp.getRequiredQuantity()))
                .toList();

        Set<UUID> vehicleLocationIds = techData.stream()
                .map(TechnicianScoringData::vehicleStockLocationId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        PartsAvailabilityResult partsResult;
        boolean partsDataDegraded = false;
        try {
            partsResult = stockQueryService.batchCheckAvailability(
                    new PartsAvailabilityQuery(requiredParts, vehicleLocationIds, Set.of()));
        } catch (Exception e) {
            partsResult = PartsAvailabilityResult.empty();
            partsDataDegraded = true;
        }

        // Build scoring contexts
        double totalBookedHours = techData.stream().mapToDouble(TechnicianScoringData::bookedHours).sum();
        double teamMeanBookedHours = techData.isEmpty() ? 0.0 : totalBookedHours / techData.size();

        Set<String> requiredCerts = requirements.requiredCertificationCodes();
        List<ScoringContext> contexts = new ArrayList<>();

        for (UUID tid : eligibleIds) {
            TechnicianScoringData td = techById.get(tid);
            if (td == null) continue;

            TravelMatrixEntry travelEntry = travelByTechId.get(tid);
            TravelTimeEstimate travelEstimate = travelEntry != null
                    ? new TravelTimeEstimate(travelEntry.estimatedMinutes(), travelEntry.degraded())
                    : TravelTimeEstimate.DEGRADED;

            PartsAvailabilityStatus partsStatus = PartsAvailabilityStatus.FULLY_STOCKED;
            if (td.vehicleStockLocationId() != null) {
                CandidateAvailabilityResult candidateResult =
                        partsResult.byVehicleLocation().get(td.vehicleStockLocationId());
                if (candidateResult != null) {
                    partsStatus = candidateResult.status();
                } else if (!requiredParts.isEmpty()) {
                    partsStatus = PartsAvailabilityStatus.UNAVAILABLE;
                }
            } else if (!requiredParts.isEmpty()) {
                partsStatus = PartsAvailabilityStatus.COLLECTABLE;
            }

            contexts.add(new ScoringContext(
                    tid,
                    td.certificationCodes(),
                    requiredCerts,
                    0,
                    travelEstimate,
                    td.bookedHours(),
                    teamMeanBookedHours,
                    partsStatus
            ));
        }

        final boolean finalPartsDataDegraded = partsDataDegraded;

        List<ScoredCandidate> ranked = scoringEngine.rank(contexts, weights);

        // Persist snapshot of full ranked list
        Instant generatedAt = Instant.now();
        String weightSetVersion = String.valueOf(weights.hashCode());

        RecommendationSnapshot snapshot = new RecommendationSnapshot(
                workOrder.getId(), generatedAt, actorId, weightSetVersion,
                travelDegraded, finalPartsDataDegraded, candidatePoolSize, truncated);
        snapshotRepository.save(snapshot);

        List<RecommendationSnapshotCandidate> snapshotCandidates = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            ScoredCandidate sc = ranked.get(i);
            snapshotCandidates.add(new RecommendationSnapshotCandidate(
                    snapshot.getId(), sc.technicianId(), i + 1, sc.compositeScore(),
                    toJson(sc.breakdown())));
        }
        candidateRepository.saveAll(snapshotCandidates);

        // Apply keyset pagination
        List<ScoredCandidate> page = applyPageCursor(ranked, cursorToken, pageSize);
        boolean hasNext = page.size() > pageSize;
        List<ScoredCandidate> pageData = hasNext ? page.subList(0, pageSize) : page;

        // Build next-cursor link
        String nextCursor = null;
        if (hasNext && !pageData.isEmpty()) {
            ScoredCandidate last = pageData.get(pageData.size() - 1);
            nextCursor = cursorCodec.encode(last.compositeScore(), last.technicianId());
        }

        // Assemble DTOs
        List<CandidateDto> dtoList = new ArrayList<>(pageData.size());
        for (int i = 0; i < pageData.size(); i++) {
            ScoredCandidate sc = pageData.get(i);
            int globalRank = ranked.indexOf(sc) + 1;
            TechnicianScoringData td = techById.get(sc.technicianId());
            String name = td != null ? td.displayName() : null;

            List<FactorDto> factors = sc.breakdown().stream()
                    .map(fb -> new FactorDto(fb.factorCode(), fb.rawValue(), fb.normalisedValue(),
                            fb.weight(), fb.weightedContribution(), fb.explanation(), fb.degraded()))
                    .toList();

            boolean candidateDegraded = travelByTechId.containsKey(sc.technicianId())
                    && travelByTechId.get(sc.technicianId()).degraded();

            dtoList.add(new CandidateDto(sc.technicianId(), name, globalRank,
                    sc.compositeScore(), factors, candidateDegraded));
        }

        // Build exclusion summary
        Map<String, Long> exclusionCounts = eligibility.excluded().stream()
                .collect(Collectors.groupingBy(
                        ec -> ec.reason().name(), Collectors.counting()));
        List<ExclusionSummaryEntry> exclusionSummary = exclusionCounts.entrySet().stream()
                .map(e -> new ExclusionSummaryEntry(e.getKey(), e.getValue().intValue()))
                .toList();

        RecommendationResponse.Page pageMeta = new RecommendationResponse.Page(pageData.size(), hasNext);
        RecommendationResponse.Links links = new RecommendationResponse.Links(nextCursor);
        RecommendationResponse.Meta meta = new RecommendationResponse.Meta(
                snapshot.getId(), generatedAt, weightSetVersion,
                travelDegraded, false, candidatePoolSize, truncated, exclusionSummary);

        return new RecommendationResponse(dtoList, pageMeta, links, meta);
    }

    private void assertAssignableState(WorkOrder workOrder) {
        if (!ASSIGNABLE_STATES.contains(workOrder.getState())) {
            throw new BusinessGuardException(
                    "recommendation.assignable_state",
                    "Work order must be in NEW state to generate recommendations; current state: "
                            + workOrder.getState());
        }
    }

    private WorkOrderRequirements buildRequirements(WorkOrder wo) {
        Double lat = null;
        Double lon = null;
        if (wo.getSite() != null) {
            if (wo.getSite().getLatitude() != null) lat = wo.getSite().getLatitude().doubleValue();
            if (wo.getSite().getLongitude() != null) lon = wo.getSite().getLongitude().doubleValue();
        }

        // Load competencies from repository (WorkOrder does not carry a direct collection)
        Set<String> certs = workOrderCompetencyRepository.findByWorkOrderId(wo.getId()).stream()
                .map(WorkOrderCompetency::getCompetencyCode)
                .collect(Collectors.toSet());

        return new WorkOrderRequirements(
                certs,
                wo.getScheduledWindowStart() != null ? wo.getScheduledWindowStart() : Instant.now(),
                wo.getScheduledWindowEnd() != null ? wo.getScheduledWindowEnd() : Instant.now().plusSeconds(3600),
                lat,
                lon,
                250.0
        );
    }

    // WorkOrderCompetencyRepository is needed for buildRequirements
    private WorkOrderCompetencyRepository workOrderCompetencyRepository;

    @org.springframework.beans.factory.annotation.Autowired
    void setWorkOrderCompetencyRepository(WorkOrderCompetencyRepository repo) {
        this.workOrderCompetencyRepository = repo;
    }

    private List<ScoredCandidate> applyPageCursor(List<ScoredCandidate> ranked,
                                                   String cursorToken, int pageSize) {
        if (cursorToken == null || cursorToken.isBlank()) {
            int end = Math.min(ranked.size(), pageSize + 1);
            return ranked.subList(0, end);
        }

        RecommendationCursorCodec.CursorPosition pos = cursorCodec.decode(cursorToken);

        int startIdx = ranked.size(); // default: nothing after cursor
        for (int i = 0; i < ranked.size(); i++) {
            ScoredCandidate sc = ranked.get(i);
            if (sc.compositeScore() < pos.score()
                    || (sc.compositeScore() == pos.score()
                        && sc.technicianId().compareTo(pos.technicianId()) > 0)) {
                startIdx = i;
                break;
            }
        }

        int end = Math.min(ranked.size(), startIdx + pageSize + 1);
        return ranked.subList(startIdx, end);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
