package com.fieldservice.workforce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workforce.application.PositionReportException.Reason;
import com.fieldservice.workforce.internal.TechnicianPositionEntity;
import com.fieldservice.workforce.internal.TechnicianPositionRepository;
import com.fieldservice.workforce.web.dto.PositionUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles purpose-limited position reports from technicians.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Reports accepted only while technician has an EN_ROUTE or IN_PROGRESS job.</li>
 *   <li>At most one report per 30-second window (rate-limited via Redis).</li>
 *   <li>Coordinates are never written to logs or event payloads.</li>
 *   <li>Persisted latitude/longitude are field-encrypted by JPA converter.</li>
 *   <li>A missing or rejected position never blocks dispatch or lifecycle transitions.</li>
 * </ul>
 */
@Service
public class PositionReportingService {

    private static final Logger log = LoggerFactory.getLogger(PositionReportingService.class);

    private static final List<WorkOrderState> ACTIVE_STATES =
            List.of(WorkOrderState.EN_ROUTE, WorkOrderState.IN_PROGRESS);

    static final Duration RATE_LIMIT_WINDOW  = Duration.ofSeconds(30);
    static final Duration CACHE_TTL          = Duration.ofSeconds(60);
    static final Duration MAX_STALENESS      = Duration.ofMinutes(5);
    private static final int RETENTION_DAYS  = 90;

    static final String RATE_LIMIT_KEY_PREFIX = "pos:rl:";
    static final String CACHE_KEY_PREFIX      = "technician:";
    static final String CACHE_KEY_SUFFIX      = ":position";

    private final TechnicianPositionRepository positionRepository;
    private final WorkOrderRepository          workOrderRepository;
    private final StringRedisTemplate          redis;
    private final ObjectMapper                 objectMapper;

    public PositionReportingService(TechnicianPositionRepository positionRepository,
                                    WorkOrderRepository workOrderRepository,
                                    StringRedisTemplate redis,
                                    ObjectMapper objectMapper) {
        this.positionRepository = positionRepository;
        this.workOrderRepository = workOrderRepository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Validates and stores a position update for a technician.
     *
     * @throws PositionReportException with {@link Reason#STALE_TIMESTAMP} if capturedAt
     *         is more than 5 minutes old or in the future
     * @throws PositionReportException with {@link Reason#NO_ACTIVE_JOB} if the technician
     *         has no EN_ROUTE or IN_PROGRESS work order (purpose limitation)
     * @throws PositionReportException with {@link Reason#RATE_LIMITED} if the per-technician
     *         30-second window has already been used
     */
    @Transactional
    public void report(UUID technicianId, PositionUpdateRequest req) {
        // Freshness guard: reject positions older than 5 min or in the future
        Instant now = Instant.now();
        long ageSeconds = ChronoUnit.SECONDS.between(req.capturedAt(), now);
        if (ageSeconds > MAX_STALENESS.toSeconds() || req.capturedAt().isAfter(now)) {
            throw new PositionReportException(Reason.STALE_TIMESTAMP,
                    "capturedAt must be within the last 5 minutes and not in the future");
        }

        // Purpose check: only accept when technician has an active job
        boolean hasActiveJob = workOrderRepository.existsActiveJobForTechnician(technicianId, ACTIVE_STATES);
        if (!hasActiveJob) {
            throw new PositionReportException(Reason.NO_ACTIVE_JOB,
                    "Position reporting is only permitted during an active EN_ROUTE or IN_PROGRESS job");
        }

        // Rate limit: at most once per 30-second window
        String rlKey = RATE_LIMIT_KEY_PREFIX + technicianId;
        Boolean acquired = redis.opsForValue().setIfAbsent(rlKey, "1", RATE_LIMIT_WINDOW);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new PositionReportException(Reason.RATE_LIMITED,
                    "Position report rate limit exceeded; retry after the window expires");
        }

        // Persist (upsert: one row per technician)
        TechnicianPositionEntity entity = positionRepository.findByTechnicianId(technicianId)
                .orElseGet(() -> new TechnicianPositionEntity(
                        technicianId,
                        req.latitude().toString(),
                        req.longitude().toString(),
                        req.capturedAt(),
                        req.accuracyMetres(),
                        LocalDate.now().plusDays(RETENTION_DAYS)));
        entity.setLatitude(req.latitude().toString());
        entity.setLongitude(req.longitude().toString());
        entity.setCapturedAt(req.capturedAt());
        entity.setAccuracyMetres(req.accuracyMetres());
        entity.setRetainUntil(LocalDate.now().plusDays(RETENTION_DAYS));
        entity.recomputeBlindIndices();
        positionRepository.save(entity);

        // Cache write: coarse position for dispatch scoring (coordinates are NOT logged)
        writeCacheEntry(technicianId, req);

        log.info("Position report accepted for technician {} accuracy={}m",
                technicianId, req.accuracyMetres());
    }

    private void writeCacheEntry(UUID technicianId, PositionUpdateRequest req) {
        try {
            Map<String, Object> payload = Map.of(
                    "lat", req.latitude(),
                    "lon", req.longitude(),
                    "accuracyMetres", req.accuracyMetres() != null ? req.accuracyMetres() : 0,
                    "capturedAt", req.capturedAt().toString()
            );
            String json = objectMapper.writeValueAsString(payload);
            String key  = CACHE_KEY_PREFIX + technicianId + CACHE_KEY_SUFFIX;
            redis.opsForValue().set(key, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            // Non-fatal: cache miss degrades gracefully in dispatch scoring
            log.warn("Failed to write position cache for technician {}: {}", technicianId, e.getMessage());
        }
    }
}
