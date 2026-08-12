package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.dispatch.scoring.CandidateScoringData;
import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.PartsAvailabilityFactor;
import com.fieldservice.dispatch.scoring.ScoredCandidate;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringEngine;
import com.fieldservice.dispatch.scoring.ScoringWeights;
import com.fieldservice.dispatch.scoring.ScoringWeightsLoader;
import com.fieldservice.dispatch.scoring.TravelTimeResult;
import com.fieldservice.dispatch.snapshot.RecommendationSnapshotRepository;
import com.fieldservice.dispatch.web.RecommendationCursor;
import com.fieldservice.dispatch.web.dto.CandidateDto;
import com.fieldservice.dispatch.web.dto.ExclusionSummaryDto;
import com.fieldservice.dispatch.web.dto.FactorDto;
import com.fieldservice.dispatch.web.dto.RecommendationMeta;
import com.fieldservice.dispatch.web.dto.RecommendationResponse;
import com.fieldservice.geo.api.Coordinates;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import com.fieldservice.workforce.api.TechnicianPositionCachePort;
import com.fieldservice.inventory.api.CandidateAvailability;
import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.StockQueryService;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.repository.WorkOrderRequiredCompetencyRepository;
import com.fieldservice.workorder.web.AssignmentWarning;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the full recommendation pipeline:
 * eligibility → travel-time → scoring → snapshot → response.
 *
 * <h3>Transaction strategy</h3>
 * <p>Work order loading and snapshot persistence each run in short, separate transactions.
 * The travel-time HTTP call is deliberately made <em>outside</em> any transaction so the
 * database connection is not held open during a potentially slow provider round trip.
 *
 * <h3>Degradation</h3>
 * <p>If the travel provider is unavailable, {@link TravelTimePort} returns Haversine-derived
 * estimates with {@code degraded=true}. The recommendation is still returned — the
 * {@code travelEstimateDegraded} flag in the response signals reduced estimate quality.
 */
@Service
public class RecommendationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecommendationOrchestrator.class);

    /** Pool size cap. Mirrors {@code CandidateReadRepository.MAX_CANDIDATE_SIZE}. */
    private static final int MAX_POOL_SIZE = 200;

    /** Server-maximum page size — requests above this are clamped, not rejected. */
    static final int MAX_PAGE_SIZE = 50;

    private static final int MIN_PAGE_SIZE = 1;

    /** Default reach radius used when the work order has no explicit reach configuration. */
    private static final double DEFAULT_REACH_KM = 200.0;

    private static final long PARTS_FRESHNESS_SECONDS = 60L;

    private final WorkOrderRepository               workOrderRepository;
    private final WorkOrderRequiredCompetencyRepository competencyRepository;
    private final ScopedQueryExecutor               scopedQueryExecutor;
    private final EligibilityService                eligibilityService;
    private final ScoringDataLoader                 scoringDataLoader;
    private final TravelTimePort                    travelTimePort;
    private final ScoringEngine                     scoringEngine;
    private final ScoringWeightsLoader              weightsLoader;
    private final RecommendationSnapshotRepository  snapshotRepository;
    private final RecommendationCursor              cursor;
    private final StockQueryService                 stockQueryService;
    private final TechnicianPositionCachePort       positionCache;
    private final PartsWarningAssembler             partsWarningAssembler;
    private final NamedParameterJdbcTemplate        namedJdbc;
    private final Timer                             durationTimer;
    private final Counter                           partsDegradedCounter;

    public RecommendationOrchestrator(
            WorkOrderRepository workOrderRepository,
            WorkOrderRequiredCompetencyRepository competencyRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            EligibilityService eligibilityService,
            ScoringDataLoader scoringDataLoader,
            TravelTimePort travelTimePort,
            ScoringEngine scoringEngine,
            ScoringWeightsLoader weightsLoader,
            RecommendationSnapshotRepository snapshotRepository,
            RecommendationCursor cursor,
            StockQueryService stockQueryService,
            TechnicianPositionCachePort positionCache,
            PartsWarningAssembler partsWarningAssembler,
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry) {
        this.workOrderRepository  = workOrderRepository;
        this.competencyRepository = competencyRepository;
        this.scopedQueryExecutor  = scopedQueryExecutor;
        this.eligibilityService   = eligibilityService;
        this.scoringDataLoader    = scoringDataLoader;
        this.travelTimePort       = travelTimePort;
        this.scoringEngine        = scoringEngine;
        this.weightsLoader        = weightsLoader;
        this.snapshotRepository   = snapshotRepository;
        this.cursor               = cursor;
        this.stockQueryService    = stockQueryService;
        this.positionCache        = positionCache;
        this.partsWarningAssembler = partsWarningAssembler;
        this.namedJdbc            = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.durationTimer = Timer.builder("dispatch.recommendation.duration")
                .description("End-to-end recommendation pipeline latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.partsDegradedCounter = Counter.builder("dispatch.parts.degraded")
                .description("Number of times parts availability data could not be loaded")
                .register(meterRegistry);
    }

    /**
     * Runs the recommendation pipeline and returns a keyset-paginated response.
     *
     * @param workOrderId  work order to recommend for
     * @param accessScope  caller's row-scope (determines allowed work orders)
     * @param actorId      authenticated principal UUID for audit
     * @param rawCursor    opaque cursor from the client (null for first page)
     * @param requestedSize desired page size (clamped to [{@value #MIN_PAGE_SIZE}, {@value #MAX_PAGE_SIZE}])
     * @param requestBaseUrl base URL for building the {@code next} link (e.g. {@code https://api.example.com})
     * @return recommendation response envelope
     * @throws ScopedAccessDeniedException if the work order is not found or out of scope
     * @throws BusinessGuardException      if the work order is not in an assignable state
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public RecommendationResponse recommend(
            UUID workOrderId,
            AccessScope accessScope,
            UUID actorId,
            String rawCursor,
            int requestedSize,
            String requestBaseUrl) {

        return durationTimer.record(() -> doRecommend(
                workOrderId, accessScope, actorId, rawCursor, requestedSize, requestBaseUrl));
    }

    private RecommendationResponse doRecommend(
            UUID workOrderId,
            AccessScope accessScope,
            UUID actorId,
            String rawCursor,
            int requestedSize,
            String requestBaseUrl) {

        int pageSize = Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, requestedSize));
        long start   = System.currentTimeMillis();

        // ── 1. Load work order (scoped read, short transaction) ──────────────────
        WorkOrder workOrder = loadWorkOrder(workOrderId, accessScope);

        // ── 2. Assignable-state guard ─────────────────────────────────────────────
        if (workOrder.getState() != WorkOrderStatus.NEW) {
            throw new BusinessGuardException(
                    "WORK_ORDER_NOT_ASSIGNABLE",
                    "Work order " + workOrderId + " is in state " + workOrder.getState()
                            + " and cannot be recommended for. Only NEW work orders are assignable.");
        }

        // ── 3. Required competencies ──────────────────────────────────────────────
        List<String> requiredCerts = competencyRepository.findByWorkOrderId(workOrderId)
                .stream()
                .map(c -> c.getCertificationCode())
                .toList();

        // ── 4. Build WorkOrderRequirements ────────────────────────────────────────
        Double siteLat = workOrder.getSite() != null && workOrder.getSite().getLatitude() != null
                ? workOrder.getSite().getLatitude().doubleValue() : null;
        Double siteLon = workOrder.getSite() != null && workOrder.getSite().getLongitude() != null
                ? workOrder.getSite().getLongitude().doubleValue() : null;

        // Service window: today UTC (reasonable default for immediate dispatch)
        Instant windowStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd   = windowStart.plusSeconds(28_800); // +8 hours

        WorkOrderRequirements requirements = new WorkOrderRequirements(
                requiredCerts, windowStart, windowEnd, siteLat, siteLon, DEFAULT_REACH_KM);

        // ── 5. Eligibility ────────────────────────────────────────────────────────
        EligibilityResult eligibility = eligibilityService.evaluate(requirements);
        List<UUID> eligibleIds = eligibility.eligible();

        boolean truncated = eligibleIds.size() > MAX_POOL_SIZE;
        if (truncated) {
            eligibleIds = eligibleIds.subList(0, MAX_POOL_SIZE);
        }

        // ── 6. Load scoring inputs (no transaction held during travel call) ───────
        LocalDate serviceDate = LocalDate.now(ZoneOffset.UTC);
        Map<UUID, ScoringDataLoader.TechnicianScoringInput> scoringData =
                scoringDataLoader.load(eligibleIds, workOrder.getFaultCategory(), serviceDate);

        // ── 7. Batched travel-time call ───────────────────────────────────────────
        Coordinates destination = (siteLat != null && siteLon != null)
                ? new Coordinates(siteLat, siteLon) : null;

        TravelMatrixResult travelMatrix;
        boolean travelEstimateDegraded = false;

        if (destination != null && !eligibleIds.isEmpty()) {
            List<TravelTimePort.OriginRequest> origins = buildOriginRequests(eligibleIds, scoringData);
            travelMatrix = travelTimePort.estimate(origins, destination);
            travelEstimateDegraded = travelMatrix.anyDegraded();
        } else {
            travelMatrix = buildDegradedMatrix(eligibleIds);
            travelEstimateDegraded = !eligibleIds.isEmpty();
        }

        // ── 8. Build CandidateScoringData (with parts availability) ──────────────
        ScoringWeights weights = weightsLoader.get();
        Map<UUID, Integer> requiredParts = loadRequiredParts(workOrderId);
        PartsAvailabilityResult partsAvailability = buildPartsAvailability(
                requiredParts, eligibleIds, scoringData);
        if (partsAvailability.degraded()) {
            partsDegradedCounter.increment();
        }
        List<AssignmentWarning> partsWarnings = partsWarningAssembler.assemble(partsAvailability);
        List<CandidateScoringData> scoringInputs = buildScoringInputs(
                eligibleIds, scoringData, travelMatrix, partsAvailability);

        // ── 9. Score ──────────────────────────────────────────────────────────────
        double teamMeanBookedHours = eligibleIds.isEmpty() ? 0.0
                : scoringData.values().stream().mapToDouble(ScoringDataLoader.TechnicianScoringInput::bookedHours).average().orElse(0.0);

        ScoringContext context = new ScoringContext(
                requiredCerts,
                workOrder.getFaultCategory(),
                teamMeanBookedHours,
                weights.travelHorizonMinutes());

        List<ScoredCandidate> ranked = eligibleIds.isEmpty()
                ? List.of()
                : scoringEngine.score(scoringInputs, context, weights);

        // ── 10. Apply cursor-based pagination ─────────────────────────────────────
        RecommendationCursor.Payload cursorPayload = (rawCursor != null && !rawCursor.isBlank())
                ? cursor.decode(rawCursor) : null;

        int startIdx = findStartIndex(ranked, cursorPayload);
        int endIdx   = Math.min(startIdx + pageSize, ranked.size());
        List<ScoredCandidate> page = ranked.subList(startIdx, endIdx);
        boolean hasNext = endIdx < ranked.size();

        // ── 11. Persist snapshot ──────────────────────────────────────────────────
        Instant generatedAt = Instant.now();
        UUID snapshotId = UuidV7.generate();
        String weightSetVersion = weightsVersionString(weights);

        snapshotRepository.persist(
                snapshotId, workOrderId, generatedAt, actorId,
                weightSetVersion, travelEstimateDegraded, false,
                eligibility.eligible().size(), truncated, ranked);

        // ── 12. Build response ────────────────────────────────────────────────────
        String nextLink = hasNext
                ? buildNextLink(requestBaseUrl, workOrderId, pageSize,
                        cursor.encode(page.get(page.size() - 1).compositeScore(),
                                page.get(page.size() - 1).technicianId()))
                : null;

        List<ExclusionSummaryDto> exclusionSummary = buildExclusionSummary(eligibility);

        RecommendationMeta meta = new RecommendationMeta(
                snapshotId, generatedAt, weightSetVersion,
                travelEstimateDegraded, partsAvailability.degraded(),
                eligibility.eligible().size(), truncated, exclusionSummary,
                partsWarnings);

        List<CandidateDto> data = buildCandidateDtos(page, startIdx);

        log.info("dispatch.recommendation.complete workOrderId={} actor={} candidates={} "
                + "travelDegraded={} durationMs={}", workOrderId, actorId,
                ranked.size(), travelEstimateDegraded, System.currentTimeMillis() - start);

        return new RecommendationResponse(
                data,
                new RecommendationResponse.PageInfo(pageSize, hasNext),
                new RecommendationResponse.Links(nextLink),
                meta);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    protected WorkOrder loadWorkOrder(UUID workOrderId, AccessScope accessScope) {
        return scopedQueryExecutor.findById(workOrderRepository, workOrderId, accessScope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException("work_order"));
    }

    private List<TravelTimePort.OriginRequest> buildOriginRequests(
            List<UUID> eligibleIds,
            Map<UUID, ScoringDataLoader.TechnicianScoringInput> scoringData) {
        List<TravelTimePort.OriginRequest> origins = new ArrayList<>();
        for (UUID id : eligibleIds) {
            // Prefer real-time cached position (60s TTL) over static home-base site coords.
            var cached = positionCache.findCachedPosition(id);
            if (cached.isPresent()) {
                origins.add(new TravelTimePort.OriginRequest(id,
                        new Coordinates(cached.get().latitude(), cached.get().longitude())));
                continue;
            }
            ScoringDataLoader.TechnicianScoringInput input = scoringData.get(id);
            if (input != null && input.homeLatitude() != null && input.homeLongitude() != null) {
                origins.add(new TravelTimePort.OriginRequest(id,
                        new Coordinates(input.homeLatitude(), input.homeLongitude())));
            }
            // Technicians without a cached position and without a geocoded home base
            // are omitted from origins and receive a degraded TravelTimeResult.
        }
        return origins;
    }

    private TravelMatrixResult buildDegradedMatrix(List<UUID> eligibleIds) {
        List<TravelMatrixResult.Entry> entries = eligibleIds.stream()
                .map(id -> new TravelMatrixResult.Entry(id, 0, true))
                .toList();
        return new TravelMatrixResult(entries, true);
    }

    private List<CandidateScoringData> buildScoringInputs(
            List<UUID> eligibleIds,
            Map<UUID, ScoringDataLoader.TechnicianScoringInput> scoringData,
            TravelMatrixResult travelMatrix,
            PartsAvailabilityResult partsAvailability) {

        Map<UUID, TravelMatrixResult.Entry> travelByTech = travelMatrix.entries().stream()
                .collect(Collectors.toMap(TravelMatrixResult.Entry::technicianId, e -> e));

        List<CandidateScoringData> inputs = new ArrayList<>(eligibleIds.size());
        for (UUID id : eligibleIds) {
            ScoringDataLoader.TechnicianScoringInput sd = scoringData.getOrDefault(id,
                    new ScoringDataLoader.TechnicianScoringInput(id, null, null, List.of(), 0, 0.0, null));

            TravelMatrixResult.Entry travelEntry = travelByTech.get(id);
            TravelTimeResult travelTime = (travelEntry != null)
                    ? new TravelTimeResult(id, travelEntry.estimatedMinutes(), travelEntry.degraded())
                    : TravelTimeResult.degraded(id);

            UUID vehicleLocId = sd.vehicleLocationId();
            CandidateAvailability availability = vehicleLocId != null
                    ? partsAvailability.byLocationId().get(vehicleLocId) : null;
            double partsScore = availability != null
                    ? PartsAvailabilityFactor.scoreFor(availability.status()) : 1.0;

            inputs.add(new CandidateScoringData(
                    id,
                    sd.certificationCodes(),
                    sd.priorJobExperience(),
                    sd.bookedHours(),
                    travelTime,
                    partsScore,
                    availability
            ));
        }
        return inputs;
    }

    private Map<UUID, Integer> loadRequiredParts(UUID workOrderId) {
        Map<UUID, Integer> result = new HashMap<>();
        try {
            namedJdbc.query(
                    "SELECT part_id::text, quantity_required FROM work_order_required_part " +
                    "WHERE work_order_id = :woId::uuid",
                    new MapSqlParameterSource("woId", workOrderId.toString()),
                    rs -> result.put(UUID.fromString(rs.getString(1)), rs.getInt(2)));
        } catch (Exception e) {
            log.warn("dispatch.parts.load_required_failed workOrderId={} reason={}", workOrderId, e.getMessage());
        }
        return result;
    }

    private PartsAvailabilityResult buildPartsAvailability(
            Map<UUID, Integer> requiredParts,
            List<UUID> eligibleIds,
            Map<UUID, ScoringDataLoader.TechnicianScoringInput> scoringData) {

        Set<UUID> vehicleLocationIds = new HashSet<>();
        for (UUID id : eligibleIds) {
            ScoringDataLoader.TechnicianScoringInput sd = scoringData.get(id);
            if (sd != null && sd.vehicleLocationId() != null) {
                vehicleLocationIds.add(sd.vehicleLocationId());
            }
        }

        // Load all warehouse location IDs
        Set<UUID> warehouseLocationIds = new HashSet<>();
        try {
            namedJdbc.query(
                    "SELECT id::text FROM stock_location WHERE location_type = 'WAREHOUSE'",
                    new MapSqlParameterSource(),
                    rs -> warehouseLocationIds.add(UUID.fromString(rs.getString(1))));
        } catch (Exception e) {
            log.warn("dispatch.parts.load_warehouses_failed reason={}", e.getMessage());
        }

        PartsAvailabilityQuery query = new PartsAvailabilityQuery(
                requiredParts, vehicleLocationIds, warehouseLocationIds);
        try {
            return stockQueryService.queryAvailability(query);
        } catch (Exception e) {
            log.warn("dispatch.parts.availability_failed reason={}", e.getMessage());
            return new PartsAvailabilityResult(Map.of(), Instant.now(), true);
        }
    }

    private int findStartIndex(List<ScoredCandidate> ranked, RecommendationCursor.Payload payload) {
        if (payload == null) return 0;
        for (int i = 0; i < ranked.size(); i++) {
            ScoredCandidate c = ranked.get(i);
            double sc = c.compositeScore();
            UUID   id = c.technicianId();
            // First entry after the cursor: score < payload.score OR (score == payload.score AND id > payload.technicianId)
            if (sc < payload.score()
                    || (sc == payload.score() && id.compareTo(payload.technicianId()) > 0)) {
                return i;
            }
        }
        return ranked.size(); // cursor is past the end
    }

    private List<CandidateDto> buildCandidateDtos(List<ScoredCandidate> page, int pageStartRank) {
        List<CandidateDto> dtos = new ArrayList<>(page.size());
        for (int i = 0; i < page.size(); i++) {
            ScoredCandidate c = page.get(i);
            List<FactorDto> factors = c.breakdown().stream()
                    .map(this::toFactorDto)
                    .toList();
            dtos.add(new CandidateDto(
                    c.technicianId(),
                    null, // technician name not loaded (PII; clients look up by ID)
                    pageStartRank + i + 1,
                    c.compositeScore(),
                    factors,
                    c.degraded()));
        }
        return dtos;
    }

    private FactorDto toFactorDto(FactorBreakdown f) {
        if (f.partsAvailability() == null) {
            return new FactorDto(f.factorCode(), f.rawValue(), f.normalisedValue(),
                    f.weight(), f.weightedContribution(), f.explanation(), f.degraded());
        }
        CandidateAvailability av = f.partsAvailability();
        boolean stale = Duration.between(av.asOf(), Instant.now()).toSeconds() > PARTS_FRESHNESS_SECONDS;
        return new FactorDto(
                f.factorCode(), f.rawValue(), f.normalisedValue(),
                f.weight(), f.weightedContribution(), f.explanation(), f.degraded(),
                av.status().name(), av.shortfalls(), av.asOf(), stale);
    }

    private List<ExclusionSummaryDto> buildExclusionSummary(EligibilityResult eligibility) {
        return eligibility.excluded().stream()
                .collect(Collectors.groupingBy(ExcludedCandidate::reason, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new ExclusionSummaryDto(e.getKey().name(), e.getValue()))
                .toList();
    }

    private static String buildNextLink(String baseUrl, UUID workOrderId, int size, String nextCursor) {
        return baseUrl + "/api/v1/work-orders/" + workOrderId
                + "/recommendations?size=" + size + "&cursor=" + nextCursor;
    }

    private static String weightsVersionString(ScoringWeights weights) {
        long hash = Long.hashCode(weights.weights().hashCode());
        return "weights-" + Long.toHexString(hash);
    }
}
