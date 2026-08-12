package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.technician.repository.TechnicianRepository;
import com.fieldservice.workforce.api.TechnicianPositionCachePort;
import com.fieldservice.workforce.web.PositionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Purpose-limited, rate-limited position reporting for active technicians.
 *
 * <p>Coordinates are NEVER included in log messages — only technician id, accuracy bucket,
 * and outcome are emitted. This is enforced structurally: no method in this class logs
 * latitude or longitude.
 */
@Service
public class PositionReportingService {

    private static final Logger log = LoggerFactory.getLogger(PositionReportingService.class);

    static final long MAX_AGE_SECONDS   = 300L;
    static final long MAX_FUTURE_MILLIS = 5_000L;

    private final TechnicianRepository            technicianRepository;
    private final TechnicianPositionRepository    positionRepository;
    private final TechnicianPositionCacheGateway  cacheGateway;
    private final JdbcTemplate                    jdbc;
    private final int                             retentionDays;

    public PositionReportingService(
            TechnicianRepository technicianRepository,
            TechnicianPositionRepository positionRepository,
            TechnicianPositionCacheGateway cacheGateway,
            JdbcTemplate jdbc,
            @Value("${app.workforce.position.retention-days:90}") int retentionDays) {
        this.technicianRepository = technicianRepository;
        this.positionRepository   = positionRepository;
        this.cacheGateway         = cacheGateway;
        this.jdbc                 = jdbc;
        this.retentionDays        = retentionDays;
    }

    /**
     * Records a position report from the authenticated technician.
     *
     * @param userId  JWT subject (resolved from the access token — never from client body)
     * @param request validated position payload
     * @throws com.fieldservice.platform.api.exception.NotFoundException if no technician profile for userId
     * @throws BusinessGuardException with field errors on validation failure
     * @throws NoActiveJobException on purpose-limitation failure
     * @throws PositionRateLimitedException when the rate-limit window is active
     */
    @PreAuthorize("hasRole('TECHNICIAN')")
    @Transactional
    public void reportPosition(UUID userId, PositionRequest request) {
        // ── 1. Resolve technician ──────────────────────────────────────────────
        var technician = technicianRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No technician profile for userId " + userId));
        UUID technicianId = technician.getId();

        // ── 2. Freshness validation ────────────────────────────────────────────
        List<FieldError> fieldErrors = new ArrayList<>();
        Instant now = Instant.now();
        Instant capturedAt = request.capturedAt();

        if (capturedAt.isAfter(now.plusMillis(MAX_FUTURE_MILLIS))) {
            fieldErrors.add(new FieldError("capturedAt", "capturedAt is in the future"));
        }
        if (now.minus(MAX_AGE_SECONDS, ChronoUnit.SECONDS).isAfter(capturedAt)) {
            fieldErrors.add(new FieldError("capturedAt",
                    "capturedAt is older than " + MAX_AGE_SECONDS + " seconds"));
        }

        if (!fieldErrors.isEmpty()) {
            throw new PositionValidationException(fieldErrors);
        }

        // ── 3. Purpose check: active job in EN_ROUTE or IN_PROGRESS ───────────
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order " +
                "WHERE assigned_technician_id = ? AND state IN ('EN_ROUTE','IN_PROGRESS')",
                Integer.class, technicianId);
        if (activeCount == null || activeCount == 0) {
            log.info("position.report.no_active_job technicianId={}", technicianId);
            throw new NoActiveJobException(technicianId.toString());
        }

        // ── 4. Rate limit (30-second window) ──────────────────────────────────
        if (!cacheGateway.tryAcquireRateLimit(technicianId)) {
            log.debug("position.report.rate_limited technicianId={}", technicianId);
            throw new PositionRateLimitedException();
        }

        // ── 5. Cache write (60-second TTL) — never logs coordinates ───────────
        String latStr = request.latitude().toPlainString();
        String lonStr = request.longitude().toPlainString();
        try {
            cacheGateway.writeCachedPosition(technicianId,
                    new TechnicianPositionCachePort.CachedPosition(
                            request.latitude().doubleValue(),
                            request.longitude().doubleValue(),
                            request.accuracyMetres(),
                            capturedAt));
        } catch (Exception e) {
            // Cache write failure never blocks the response
            log.warn("position.cache.write_error technicianId={} reason={}", technicianId, e.getMessage());
        }

        // ── 6. Persist encrypted row ──────────────────────────────────────────
        LocalDate retainUntil = LocalDate.now().plusDays(retentionDays);
        positionRepository.save(new TechnicianPositionEntity(
                technicianId, latStr, lonStr,
                capturedAt, request.accuracyMetres(), retainUntil));

        String accuracyBucket = accuracyBucket(request.accuracyMetres());
        log.info("position.report.accepted technicianId={} accuracyBucket={}", technicianId, accuracyBucket);
    }

    /** Buckets accuracy into coarse categories for logging — never logs the raw value as a coordinate. */
    private static String accuracyBucket(int metres) {
        if (metres <= 10)  return "HIGH";
        if (metres <= 50)  return "MEDIUM";
        if (metres <= 200) return "LOW";
        return "POOR";
    }
}
