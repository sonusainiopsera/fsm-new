package com.fieldservice.inventory.application;

import com.fieldservice.inventory.api.CandidateAvailabilityResult;
import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;
import com.fieldservice.inventory.api.PartShortfall;
import com.fieldservice.inventory.api.RequiredPartQuantity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core parts availability lookup: two bounded set-based queries over {@code stock_balance},
 * then in-memory classification per candidate location.
 *
 * <p>Query count is constant regardless of candidate count:
 * <ol>
 *   <li>Query 1 — stock for all candidate vehicle locations AND all IN (:parts)</li>
 *   <li>Query 2 — stock for all reachable warehouse locations AND all IN (:parts)</li>
 * </ol>
 * IN-clause chunking keeps individual queries within the driver parameter limit of 32_767.
 *
 * <p>Classification logic (in-memory, no extra queries):
 * <ol>
 *   <li>FULLY_STOCKED — vehicle has all required parts in sufficient quantity.</li>
 *   <li>PARTIALLY_STOCKED — vehicle covers some lines; shortfalls exist.</li>
 *   <li>COLLECTABLE — shortfalls are all covered by at least one reachable warehouse.</li>
 *   <li>UNAVAILABLE — at least one part cannot be sourced from vehicle or warehouse.</li>
 * </ol>
 */
@Component
class PartsAvailabilityLookupService {

    private static final Logger log = LoggerFactory.getLogger(PartsAvailabilityLookupService.class);
    private static final int IN_CHUNK_SIZE = 1_000;

    private static final String STOCK_SQL = """
            SELECT stock_location_id::text, part_id::text, quantity_on_hand
            FROM stock_balance
            WHERE part_id = ANY(CAST(:parts AS uuid[]))
              AND stock_location_id = ANY(CAST(:locations AS uuid[]))
            """;

    @PersistenceContext
    private EntityManager em;

    private final Timer lookupTimer;
    private final PartsAvailabilityMetrics metrics;

    PartsAvailabilityLookupService(MeterRegistry registry, PartsAvailabilityMetrics metrics) {
        this.lookupTimer = Timer.builder("inventory.availability.lookup.duration")
                .description("Time for a batch parts availability lookup")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.metrics = metrics;
    }

    /**
     * Performs the two bounded stock queries and classifies each candidate location.
     * Returns {@link PartsAvailabilityResult#empty()} when there are no required parts.
     */
    @Transactional(readOnly = true)
    PartsAvailabilityResult lookup(PartsAvailabilityQuery query) {
        // Discard zero-quantity lines
        List<RequiredPartQuantity> requirements = query.requiredParts().stream()
                .filter(r -> r.requiredQuantity() > 0)
                .toList();

        if (requirements.isEmpty()) {
            return PartsAvailabilityResult.empty();
        }

        return lookupTimer.record(() -> doLookup(query, requirements));
    }

    @SuppressWarnings("unchecked")
    private PartsAvailabilityResult doLookup(PartsAvailabilityQuery query,
                                               List<RequiredPartQuantity> requirements) {
        Set<UUID> vehicleLocations = query.candidateVehicleLocationIds();
        Set<UUID> warehouseLocations = query.reachableWarehouseLocationIds();

        // Part ID → required quantity
        Map<UUID, Integer> required = requirements.stream()
                .collect(Collectors.toMap(RequiredPartQuantity::partId, RequiredPartQuantity::requiredQuantity));

        Set<UUID> partIds = required.keySet();

        // Query 1: vehicle stock
        Map<UUID, Map<UUID, Integer>> vehicleStock = queryStock(partIds, vehicleLocations);

        // Query 2: warehouse stock
        Map<UUID, Map<UUID, Integer>> warehouseStock = queryStock(partIds, warehouseLocations);

        // Warehouse shortfall coverage: for each part, what's the total across all warehouses?
        Map<UUID, Integer> warehouseTotalByPart = aggregateByPart(warehouseStock);

        Instant asOf = Instant.now();

        // Classify each vehicle location
        Map<UUID, CandidateAvailabilityResult> byVehicleLocation = new HashMap<>();
        for (UUID locId : vehicleLocations) {
            Map<UUID, Integer> vanStock = vehicleStock.getOrDefault(locId, Map.of());
            CandidateAvailabilityResult result = classify(locId, required, vanStock, warehouseTotalByPart);
            byVehicleLocation.put(locId, result);
        }

        // Job verdict: best status across all candidate locations
        PartsAvailabilityStatus jobVerdict = byVehicleLocation.values().stream()
                .map(CandidateAvailabilityResult::status)
                .min(Enum::compareTo)
                .orElse(PartsAvailabilityStatus.UNAVAILABLE);

        return new PartsAvailabilityResult(byVehicleLocation, jobVerdict, asOf, false);
    }

    private CandidateAvailabilityResult classify(
            UUID locationId,
            Map<UUID, Integer> required,
            Map<UUID, Integer> vanStock,
            Map<UUID, Integer> warehouseTotalByPart) {

        List<PartShortfall> shortfalls = new ArrayList<>();

        for (Map.Entry<UUID, Integer> entry : required.entrySet()) {
            UUID partId = entry.getKey();
            int requiredQty = entry.getValue();
            int vanQty = vanStock.getOrDefault(partId, 0);

            if (vanQty < requiredQty) {
                shortfalls.add(new PartShortfall(partId, null, requiredQty, vanQty));
            }
        }

        if (shortfalls.isEmpty()) {
            return new CandidateAvailabilityResult(locationId, PartsAvailabilityStatus.FULLY_STOCKED, List.of());
        }

        // Check if warehouses cover all shortfalls
        boolean allCoveredByWarehouse = shortfalls.stream().allMatch(sf -> {
            int warehouseQty = warehouseTotalByPart.getOrDefault(sf.partId(), 0);
            return warehouseQty >= sf.requested();
        });

        boolean allShortfalls = required.entrySet().stream().allMatch(e -> {
            int vanQty = vanStock.getOrDefault(e.getKey(), 0);
            return vanQty < e.getValue();
        });

        PartsAvailabilityStatus status;
        if (allShortfalls) {
            status = allCoveredByWarehouse
                    ? PartsAvailabilityStatus.COLLECTABLE
                    : PartsAvailabilityStatus.UNAVAILABLE;
        } else {
            status = allCoveredByWarehouse
                    ? PartsAvailabilityStatus.COLLECTABLE
                    : PartsAvailabilityStatus.PARTIALLY_STOCKED;
        }

        return new CandidateAvailabilityResult(locationId, status, shortfalls);
    }

    /**
     * Queries stock balances for the given parts and locations.
     * Chunked to stay within driver parameter limits.
     *
     * @return Map[locationId → Map[partId → quantityOnHand]]
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, Map<UUID, Integer>> queryStock(Set<UUID> partIds, Set<UUID> locationIds) {
        Map<UUID, Map<UUID, Integer>> result = new HashMap<>();
        if (partIds.isEmpty() || locationIds.isEmpty()) {
            return result;
        }

        List<UUID> partList = List.copyOf(partIds);
        List<UUID> locList  = List.copyOf(locationIds);

        // Chunk both dimensions but only one combined query per chunk pair to stay bounded
        for (int pi = 0; pi < partList.size(); pi += IN_CHUNK_SIZE) {
            List<UUID> partChunk = partList.subList(pi, Math.min(pi + IN_CHUNK_SIZE, partList.size()));
            for (int li = 0; li < locList.size(); li += IN_CHUNK_SIZE) {
                List<UUID> locChunk = locList.subList(li, Math.min(li + IN_CHUNK_SIZE, locList.size()));
                String partsLiteral = toPgArrayLiteral(partChunk);
                String locsLiteral  = toPgArrayLiteral(locChunk);

                List<Object[]> rows = em.createNativeQuery(STOCK_SQL)
                        .setParameter("parts", partsLiteral)
                        .setParameter("locations", locsLiteral)
                        .getResultList();

                for (Object[] row : rows) {
                    UUID locId  = UUID.fromString((String) row[0]);
                    UUID partId = UUID.fromString((String) row[1]);
                    int qty     = ((Number) row[2]).intValue();
                    result.computeIfAbsent(locId, k -> new HashMap<>()).put(partId, qty);
                }
            }
        }
        return result;
    }

    private static Map<UUID, Integer> aggregateByPart(Map<UUID, Map<UUID, Integer>> stockByLocation) {
        Map<UUID, Integer> total = new HashMap<>();
        for (Map<UUID, Integer> byPart : stockByLocation.values()) {
            for (Map.Entry<UUID, Integer> entry : byPart.entrySet()) {
                total.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return total;
    }

    private static String toPgArrayLiteral(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (UUID id : ids) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
