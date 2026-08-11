package com.fieldservice.sla.internal;

import com.fieldservice.sla.SlaDeadlineCalculator;
import com.fieldservice.sla.SlaDeadlines;
import com.fieldservice.sla.SlaPolicy;
import com.fieldservice.sla.SlaPolicyProvider;
import com.fieldservice.sla.SlaPolicyUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal implementation of {@link SlaPolicyProvider} and {@link SlaDeadlineCalculator}.
 *
 * <p>Policy resolution is Caffeine-cached with a 60-second TTL (configured in
 * {@link SlaConfiguration}). The cache is evicted on every admin write.
 *
 * <p>When the cache load path fails (e.g. database timeout), the exception propagates
 * and the {@code sla_policy_resolution_failures_total} counter is incremented.
 */
@Service
@Transactional(readOnly = true)
class SlaPolicyService implements SlaPolicyProvider, SlaDeadlineCalculator, com.fieldservice.sla.SlaClockPausePort {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicyService.class);
    static final String CACHE_NAME = "sla-policy";

    private final SlaPolicyRepository slaPolicyRepository;
    private final SlaClockPauseRepository pauseRepository;
    private final Clock clock;
    private final Counter resolutionFailureCounter;

    SlaPolicyService(SlaPolicyRepository slaPolicyRepository,
                     SlaClockPauseRepository pauseRepository,
                     Clock clock,
                     MeterRegistry meterRegistry) {
        this.slaPolicyRepository = slaPolicyRepository;
        this.pauseRepository = pauseRepository;
        this.clock = clock;
        this.resolutionFailureCounter = Counter.builder("sla_policy_resolution_failures_total")
                .description("Number of times SLA policy resolution failed for a priority")
                .register(meterRegistry);
    }

    // ── SlaPolicyProvider ────────────────────────────────────────────────────

    @Override
    @Cacheable(cacheNames = CACHE_NAME, key = "#priority + ':' + #at.toEpochMilli()")
    public SlaPolicy resolveActivePolicy(String priority, Instant at) {
        List<com.fieldservice.domain.sla.SlaPolicy> rows =
                slaPolicyRepository.findActiveForPriorityAt(priority, at);

        if (rows.isEmpty()) {
            resolutionFailureCounter.increment();
            log.error("sla.policy_resolution_failure: priority={}, at={}", priority, at);
            throw new SlaPolicyUnavailableException(priority);
        }

        if (rows.size() > 1) {
            log.warn("sla.policy_overlap_detected: priority={}, count={} — using latest effective_from",
                    priority, rows.size());
        }

        com.fieldservice.domain.sla.SlaPolicy entity = rows.get(0);
        return toDto(entity);
    }

    // ── SlaDeadlineCalculator ────────────────────────────────────────────────

    @Override
    public SlaDeadlines calculate(String priority, Instant createdAt) {
        SlaPolicy policy = resolveActivePolicy(priority, createdAt);

        Instant responseDueAt    = createdAt.plus(Duration.ofMinutes(policy.responseMinutes()));
        Instant resolutionDueAt  = createdAt.plus(Duration.ofMinutes(policy.resolutionMinutes()));

        // at_risk instant = createdAt + (resolutionMinutes * atRiskFraction) minutes
        long atRiskMinutes = (long) (policy.resolutionMinutes() * policy.atRiskFraction().doubleValue());
        Instant atRiskAt = createdAt.plus(Duration.ofMinutes(atRiskMinutes));

        return new SlaDeadlines(responseDueAt, resolutionDueAt, atRiskAt);
    }

    @Override
    public Instant effectiveResolutionDueAt(UUID workOrderId, Instant resolutionDueAt) {
        List<SlaClockPause> pauses = pauseRepository.findByWorkOrderId(workOrderId);
        Instant now = clock.instant();

        long accruedSeconds = 0L;
        for (SlaClockPause pause : pauses) {
            Instant end = pause.getResumedAt() != null ? pause.getResumedAt() : now;
            long secs = Duration.between(pause.getPausedAt(), end).getSeconds();
            if (secs > 0) {
                accruedSeconds += secs;
            }
        }

        return resolutionDueAt.plusSeconds(accruedSeconds);
    }

    // ── Admin write operations (cache-evicting) ──────────────────────────────

    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    @Transactional
    public com.fieldservice.domain.sla.SlaPolicy createPolicy(
            com.fieldservice.domain.sla.SlaPolicy policy) {
        return slaPolicyRepository.save(policy);
    }

    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    @Transactional
    public com.fieldservice.domain.sla.SlaPolicy supersede(
            UUID priorId,
            com.fieldservice.domain.sla.SlaPolicy newPolicy,
            Instant effectiveTo) {
        com.fieldservice.domain.sla.SlaPolicy prior = slaPolicyRepository.findById(priorId)
                .orElseThrow(() -> new com.fieldservice.platform.exception.NotFoundException(
                        "SlaPolicy", priorId));
        prior.setEffectiveTo(effectiveTo);
        prior.setActive(false);
        slaPolicyRepository.save(prior);
        return slaPolicyRepository.save(newPolicy);
    }

    /** Returns all policies for admin listing. */
    @Transactional(readOnly = true)
    public List<com.fieldservice.domain.sla.SlaPolicy> findAll() {
        return slaPolicyRepository.findAllOrderedByPriorityAndEffectiveFrom();
    }

    // ── SLA clock pause management ───────────────────────────────────────────

    @Transactional
    public void openPause(UUID workOrderId, String holdReasonCode, Instant pausedAt) {
        SlaClockPause pause = SlaClockPause.open(workOrderId, holdReasonCode, pausedAt);
        pauseRepository.save(pause);
    }

    @Transactional
    public void closePause(UUID workOrderId, Instant resumedAt) {
        pauseRepository.findByWorkOrderIdAndResumedAtIsNull(workOrderId)
                .ifPresent(p -> {
                    p.resume(resumedAt);
                    pauseRepository.save(p);
                });
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    static SlaPolicy toDto(com.fieldservice.domain.sla.SlaPolicy e) {
        return new SlaPolicy(
                e.getId(),
                e.getPriority(),
                e.getResponseMinutes(),
                e.getResolutionMinutes(),
                e.getAtRiskFraction(),
                e.getEffectiveFrom(),
                e.getEffectiveTo(),
                e.isActive(),
                e.getVersion());
    }
}
