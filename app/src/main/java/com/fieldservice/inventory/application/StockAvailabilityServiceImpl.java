package com.fieldservice.inventory.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.inventory.api.AvailabilityStatus;
import com.fieldservice.inventory.api.CandidateAvailability;
import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.PartShortfall;
import com.fieldservice.inventory.api.StockQueryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Set-based batch availability lookup.
 *
 * <p>Issues exactly three SQL statements regardless of candidate count:
 * <ol>
 *   <li>Part number lookup (bounded by partIds)</li>
 *   <li>Vehicle-location balances (bounded by partIds × vehicleLocationIds)</li>
 *   <li>Warehouse balances (bounded by partIds × warehouseLocationIds)</li>
 * </ol>
 * IN clauses are chunked to {@value #CHUNK_SIZE} to stay within driver parameter limits.
 * Results are cached in Redis (TTL ≤ 60 s) with graceful degradation on cache failure.
 */
@Service
public class StockAvailabilityServiceImpl implements StockQueryService {

    private static final Logger log = LoggerFactory.getLogger(StockAvailabilityServiceImpl.class);

    static final int CHUNK_SIZE = 1000;
    private static final String CACHE_KEY_PREFIX = "parts-avail:";
    private static final TypeReference<PartsAvailabilityResult> RESULT_TYPE =
            new TypeReference<>() {};

    private final NamedParameterJdbcTemplate namedJdbc;
    private final StringRedisTemplate         redisTemplate;
    private final ObjectMapper                objectMapper;
    private final Duration                    cacheTtl;
    private final Timer                       lookupTimer;
    private final Counter                     degradedCounter;
    private final AtomicLong                  cacheHits   = new AtomicLong(0);
    private final AtomicLong                  cacheMisses = new AtomicLong(0);

    public StockAvailabilityServiceImpl(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.inventory.parts-cache.ttl-seconds:60}") long cacheTtlSeconds) {

        this.namedJdbc      = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.cacheTtl       = Duration.ofSeconds(Math.min(cacheTtlSeconds, 60));

        this.lookupTimer = Timer.builder("inventory.parts.availability.lookup")
                .description("Parts availability lookup latency")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.degradedCounter = Counter.builder("inventory.parts.availability.degraded")
                .description("Parts availability queries that bypassed cache due to Redis failure")
                .register(meterRegistry);

        meterRegistry.gauge("inventory.parts.availability.cache.hit_ratio",
                this,
                impl -> {
                    long hits   = impl.cacheHits.get();
                    long misses = impl.cacheMisses.get();
                    long total  = hits + misses;
                    return total == 0 ? 0.0 : (double) hits / total;
                });
    }

    @Override
    @Transactional(readOnly = true)
    public PartsAvailabilityResult queryAvailability(PartsAvailabilityQuery query) {
        // 1. Filter zero-quantity lines and short-circuit for empty requirements
        Map<UUID, Integer> required = query.requiredQuantities().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

        if (required.isEmpty() || query.vehicleLocationIds().isEmpty()) {
            return allFullyStocked(query.vehicleLocationIds(), Instant.now());
        }

        // 2. Try Redis cache
        String cacheKey = buildCacheKey(required, query.vehicleLocationIds(), query.warehouseLocationIds());
        Optional<PartsAvailabilityResult> cached = getFromCache(cacheKey);
        if (cached.isPresent()) {
            cacheHits.incrementAndGet();
            return cached.get();
        }
        cacheMisses.incrementAndGet();

        // 3. Execute three bounded queries and classify
        return lookupTimer.record(() -> doQueryAndClassify(required, query, cacheKey));
    }

    private PartsAvailabilityResult doQueryAndClassify(
            Map<UUID, Integer> required,
            PartsAvailabilityQuery query,
            String cacheKey) {

        Set<UUID> partIds           = required.keySet();
        Set<UUID> vehicleLocationIds = query.vehicleLocationIds();
        Set<UUID> warehouseLocationIds = query.warehouseLocationIds();

        // Statement 1: part numbers
        Map<UUID, String> partNumbers = loadPartNumbers(partIds);

        // Statement 2: vehicle balances — Map<locationId, Map<partId, quantity>>
        Map<UUID, Map<UUID, Integer>> vehicleBalances = loadVehicleBalances(partIds, vehicleLocationIds);

        // Statement 3: warehouse totals — Map<partId, total quantity across all warehouses>
        Map<UUID, Integer> warehouseTotals = warehouseLocationIds.isEmpty()
                ? Map.of()
                : loadWarehouseTotals(partIds, warehouseLocationIds);

        Instant asOf = Instant.now();

        Map<UUID, CandidateAvailability> byLocationId = new HashMap<>(vehicleLocationIds.size());
        for (UUID locationId : vehicleLocationIds) {
            Map<UUID, Integer> vehicleStocks = vehicleBalances.getOrDefault(locationId, Map.of());
            CandidateAvailability avail = classify(
                    locationId, required, vehicleStocks, warehouseTotals, partNumbers, asOf);
            byLocationId.put(locationId, avail);
        }

        PartsAvailabilityResult result = new PartsAvailabilityResult(
                Collections.unmodifiableMap(byLocationId), asOf, false);

        putToCache(cacheKey, result);
        return result;
    }

    // ─── SQL statements ────────────────────────────────────────────────────────

    private Map<UUID, String> loadPartNumbers(Set<UUID> partIds) {
        List<String> idStrings = partIds.stream().map(UUID::toString).toList();
        Map<UUID, String> result = new HashMap<>();
        for (List<String> chunk : chunk(idStrings)) {
            namedJdbc.query(
                    "SELECT id::text, part_number FROM part WHERE id::text IN (:ids)",
                    new MapSqlParameterSource("ids", chunk),
                    rs -> result.put(UUID.fromString(rs.getString(1)), rs.getString(2)));
        }
        return result;
    }

    private Map<UUID, Map<UUID, Integer>> loadVehicleBalances(
            Set<UUID> partIds, Set<UUID> locationIds) {
        List<String> partIdStrs = partIds.stream().map(UUID::toString).toList();
        List<String> locIdStrs  = locationIds.stream().map(UUID::toString).toList();
        Map<UUID, Map<UUID, Integer>> result = new HashMap<>();
        for (List<String> partChunk : chunk(partIdStrs)) {
            for (List<String> locChunk : chunk(locIdStrs)) {
                namedJdbc.query(
                        "SELECT part_id::text, location_id::text, quantity_on_hand " +
                        "FROM stock_balance " +
                        "WHERE part_id::text IN (:partIds) AND location_id::text IN (:locIds)",
                        new MapSqlParameterSource()
                                .addValue("partIds", partChunk)
                                .addValue("locIds",  locChunk),
                        rs -> {
                            UUID partId = UUID.fromString(rs.getString(1));
                            UUID locId  = UUID.fromString(rs.getString(2));
                            int  qty    = rs.getInt(3);
                            result.computeIfAbsent(locId, k -> new HashMap<>()).put(partId, qty);
                        });
            }
        }
        return result;
    }

    private Map<UUID, Integer> loadWarehouseTotals(
            Set<UUID> partIds, Set<UUID> warehouseLocationIds) {
        List<String> partIdStrs = partIds.stream().map(UUID::toString).toList();
        List<String> locIdStrs  = warehouseLocationIds.stream().map(UUID::toString).toList();
        Map<UUID, Integer> totals = new HashMap<>();
        for (List<String> partChunk : chunk(partIdStrs)) {
            for (List<String> locChunk : chunk(locIdStrs)) {
                namedJdbc.query(
                        "SELECT part_id::text, SUM(quantity_on_hand) AS total " +
                        "FROM stock_balance " +
                        "WHERE part_id::text IN (:partIds) AND location_id::text IN (:locIds) " +
                        "GROUP BY part_id",
                        new MapSqlParameterSource()
                                .addValue("partIds", partChunk)
                                .addValue("locIds",  locChunk),
                        rs -> {
                            UUID partId = UUID.fromString(rs.getString(1));
                            int  total  = rs.getInt(2);
                            totals.merge(partId, total, Integer::sum);
                        });
            }
        }
        return totals;
    }

    // ─── Classification ─────────────────────────────────────────────────────────

    static CandidateAvailability classify(
            UUID locationId,
            Map<UUID, Integer> required,
            Map<UUID, Integer> vehicleStocks,
            Map<UUID, Integer> warehouseTotals,
            Map<UUID, String>  partNumbers,
            Instant            asOf) {

        List<PartShortfall> shortfalls = new ArrayList<>();
        int collectableShortfalls   = 0;
        int uncollectableShortfalls = 0;

        for (Map.Entry<UUID, Integer> e : required.entrySet()) {
            UUID partId      = e.getKey();
            int  requiredQty = e.getValue();
            int  vehicleQty  = vehicleStocks.getOrDefault(partId, 0);

            if (vehicleQty >= requiredQty) continue;

            int shortfall    = requiredQty - vehicleQty;
            int warehouseQty = warehouseTotals.getOrDefault(partId, 0);
            int available    = vehicleQty + warehouseQty;
            String partNum   = partNumbers.getOrDefault(partId, "UNKNOWN");

            shortfalls.add(new PartShortfall(partId, partNum, requiredQty, available));

            if (warehouseQty >= shortfall) {
                collectableShortfalls++;
            } else {
                uncollectableShortfalls++;
            }
        }

        AvailabilityStatus status;
        if (shortfalls.isEmpty()) {
            status = AvailabilityStatus.FULLY_STOCKED;
        } else if (uncollectableShortfalls == 0) {
            status = AvailabilityStatus.COLLECTABLE;
        } else if (collectableShortfalls > 0) {
            status = AvailabilityStatus.PARTIALLY_STOCKED;
        } else {
            status = AvailabilityStatus.UNAVAILABLE;
        }

        return new CandidateAvailability(locationId, status, shortfalls, asOf);
    }

    // ─── Cache ─────────────────────────────────────────────────────────────────

    private Optional<PartsAvailabilityResult> getFromCache(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(value, RESULT_TYPE));
        } catch (Exception e) {
            log.warn("inventory.parts.cache_read_failed key={} reason={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void putToCache(String key, PartsAvailabilityResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(key, json, cacheTtl);
        } catch (Exception e) {
            log.warn("inventory.parts.cache_write_failed key={} reason={}", key, e.getMessage());
            degradedCounter.increment();
        }
    }

    // ─── Cache key ──────────────────────────────────────────────────────────────

    /**
     * Builds a stable SHA-256 cache key from sorted parts + quantities, vehicle IDs, warehouse IDs.
     * Package-private for unit testing.
     */
    static String buildCacheKey(
            Map<UUID, Integer> required,
            Set<UUID> vehicleLocationIds,
            Set<UUID> warehouseLocationIds) {
        StringBuilder sb = new StringBuilder();
        required.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append(':').append(e.getValue()).append(';'));
        sb.append('|');
        vehicleLocationIds.stream().sorted().forEach(id -> sb.append(id).append(','));
        sb.append('|');
        warehouseLocationIds.stream().sorted().forEach(id -> sb.append(id).append(','));

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return CACHE_KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return CACHE_KEY_PREFIX + sb;
        }
    }

    // ─── Utilities ──────────────────────────────────────────────────────────────

    private static PartsAvailabilityResult allFullyStocked(Set<UUID> vehicleLocationIds, Instant asOf) {
        Map<UUID, CandidateAvailability> byLocationId = new HashMap<>(vehicleLocationIds.size());
        for (UUID locId : vehicleLocationIds) {
            byLocationId.put(locId, new CandidateAvailability(
                    locId, AvailabilityStatus.FULLY_STOCKED, List.of(), asOf));
        }
        return new PartsAvailabilityResult(byLocationId, asOf, false);
    }

    private static <T> List<List<T>> chunk(List<T> list) {
        if (list.size() <= CHUNK_SIZE) return List.of(list);
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK_SIZE) {
            chunks.add(list.subList(i, Math.min(i + CHUNK_SIZE, list.size())));
        }
        return chunks;
    }
}
