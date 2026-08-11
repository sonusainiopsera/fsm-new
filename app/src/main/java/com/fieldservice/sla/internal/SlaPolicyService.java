package com.fieldservice.sla.internal;

import com.fieldservice.sla.SlaDeadlineCalculator;
import com.fieldservice.sla.SlaDeadlineResult;
import com.fieldservice.sla.SlaPolicyProvider;
import com.fieldservice.sla.SlaPolicyUnavailableException;
import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.sla.web.AdminSlaPolicyRequest;
import com.fieldservice.platform.util.UuidV7;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements SLA policy resolution, deadline calculation, and admin management.
 *
 * <p>Cache: simple TTL-based ConcurrentHashMap keyed by priority.
 * Explicit invalidation on any admin write ensures changes propagate within the TTL window.
 */
@Service
@Transactional
public class SlaPolicyService implements SlaPolicyProvider, SlaDeadlineCalculator {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicyService.class);
    private static final long CACHE_TTL_MS = 60_000L;

    private final SlaPolicyRepository    policyRepo;
    private final SlaClockPauseRepository pauseRepo;
    private final Counter                resolutionFailureCounter;

    /** Simple TTL cache: priority → (policy, expiryEpochMs). */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SlaPolicyService(SlaPolicyRepository policyRepo,
                            SlaClockPauseRepository pauseRepo,
                            MeterRegistry meterRegistry) {
        this.policyRepo  = policyRepo;
        this.pauseRepo   = pauseRepo;
        this.resolutionFailureCounter = Counter.builder("sla_policy_resolution_failures_total")
                .description("Count of SLA policy resolution failures")
                .register(meterRegistry);
    }

    // ---- SlaPolicyProvider --------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<SlaPolicy> resolve(String priority, Instant at) {
        CacheEntry entry = cache.get(priority);
        if (entry != null && !entry.isExpired()) {
            return entry.policy;
        }
        Optional<SlaPolicy> result = loadFromRepo(priority, at);
        cache.put(priority, new CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS));
        return result;
    }

    // ---- SlaDeadlineCalculator ---------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public SlaDeadlineResult calculate(String priority, Instant createdAt) {
        Optional<SlaPolicy> policyOpt = resolve(priority, createdAt);
        if (policyOpt.isEmpty()) {
            resolutionFailureCounter.increment();
            log.error("sla_policy_unavailable priority={}", priority);
            throw new SlaPolicyUnavailableException(priority);
        }
        SlaPolicy policy = policyOpt.get();
        Instant responseDueAt    = createdAt.plus(Duration.ofMinutes(policy.getResponseMinutes()));
        Instant resolutionDueAt  = createdAt.plus(Duration.ofMinutes(policy.getResolutionMinutes()));
        long atRiskMinutes = Math.round(policy.getResolutionMinutes() * policy.getAtRiskFraction().doubleValue());
        Instant atRiskAt = createdAt.plus(Duration.ofMinutes(atRiskMinutes));
        return new SlaDeadlineResult(responseDueAt, resolutionDueAt, atRiskAt, resolutionDueAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Instant effectiveResolutionDeadline(UUID workOrderId, Instant nominalDeadline,
                                               Instant evaluatedAt) {
        List<SlaClockPause> pauses = pauseRepo.findByWorkOrderId(workOrderId);
        long totalPausedSeconds = 0L;
        for (SlaClockPause pause : pauses) {
            Instant end = pause.getResumedAt() != null ? pause.getResumedAt() : evaluatedAt;
            totalPausedSeconds += Duration.between(pause.getPausedAt(), end).getSeconds();
        }
        return nominalDeadline.plusSeconds(totalPausedSeconds);
    }

    // ---- Pause ledger (called by WorkOrderTransitionApplicationService) -----

    @Transactional
    public void openPause(UUID workOrderId, String holdReason, Instant pausedAt) {
        SlaClockPause pause = new SlaClockPause(workOrderId, holdReason, pausedAt);
        pauseRepo.save(pause);
    }

    @Transactional
    public void closePause(UUID workOrderId, Instant resumedAt) {
        pauseRepo.findByWorkOrderIdAndResumedAtIsNull(workOrderId)
                .ifPresent(p -> {
                    p.resume(resumedAt);
                    pauseRepo.save(p);
                });
    }

    // ---- Admin operations ---------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<SlaPolicy> listPolicies(Pageable pageable) {
        return policyRepo.findAll(pageable);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<SlaPolicy> findById(UUID id) {
        return policyRepo.findById(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public SlaPolicy create(AdminSlaPolicyRequest request) {
        SlaPolicy policy = new SlaPolicy(
                request.priority(),
                request.responseMinutes(),
                request.resolutionMinutes(),
                request.atRiskFraction(),
                request.effectiveFrom()
        );
        SlaPolicy saved = policyRepo.save(policy);
        invalidateCache(request.priority());
        return saved;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public SlaPolicy supersede(UUID id, AdminSlaPolicyRequest request) {
        SlaPolicy existing = policyRepo.findById(id)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException(
                        "SlaPolicy", id.toString()));

        Instant now = Instant.now();
        existing.closeAt(now);
        policyRepo.save(existing);

        SlaPolicy replacement = new SlaPolicy(
                request.priority() != null ? request.priority() : existing.getPriority(),
                request.responseMinutes(),
                request.resolutionMinutes(),
                request.atRiskFraction(),
                request.effectiveFrom() != null ? request.effectiveFrom() : now
        );
        SlaPolicy saved = policyRepo.save(replacement);
        invalidateCache(existing.getPriority());
        invalidateCache(saved.getPriority());
        return saved;
    }

    // ---- Helpers ------------------------------------------------------------

    private Optional<SlaPolicy> loadFromRepo(String priority, Instant at) {
        try {
            return policyRepo.findActiveByPriorityAt(priority, at);
        } catch (Exception ex) {
            log.error("sla_policy_cache_load_failed priority={} falling back to direct query", priority, ex);
            resolutionFailureCounter.increment();
            return policyRepo.findActiveByPriorityAt(priority, at);
        }
    }

    private void invalidateCache(String priority) {
        if (priority != null) {
            cache.remove(priority);
        }
    }

    private record CacheEntry(Optional<SlaPolicy> policy, long expiryEpochMs) {
        boolean isExpired() { return System.currentTimeMillis() > expiryEpochMs; }
    }
}
