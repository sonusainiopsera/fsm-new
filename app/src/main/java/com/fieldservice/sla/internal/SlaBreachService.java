package com.fieldservice.sla.internal;

import com.fieldservice.outbox.payload.SlaBreachedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.sla.SlaBreachAdminService;
import com.fieldservice.sla.SlaBreachDto;
import com.fieldservice.sla.SlaBreachPort;
import com.fieldservice.sla.SlaBreachReasonCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Writes, finalises and serves SLA breach records.
 *
 * <p>Detection is idempotent via the unique index on (work_order_id, breach_type).
 * Finalisation is idempotent because {@link SlaBreachEntity#writeFinalOverrun} is a no-op
 * when {@code final_overrun_minutes} is already set.
 */
@Service
class SlaBreachService implements SlaBreachPort, SlaBreachAdminService {

    private static final Logger log = LoggerFactory.getLogger(SlaBreachService.class);

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("detectedAt", "overrunMinutes");

    private final SlaBreachRepository repository;
    private final SlaRiskFlagRepository flagRepository;
    private final DomainEventPublisher eventPublisher;
    private final Counter breachDetectedCounter;

    SlaBreachService(SlaBreachRepository repository,
                     SlaRiskFlagRepository flagRepository,
                     DomainEventPublisher eventPublisher,
                     MeterRegistry meterRegistry) {
        this.repository      = repository;
        this.flagRepository  = flagRepository;
        this.eventPublisher  = eventPublisher;
        this.breachDetectedCounter = Counter.builder("sla_breach_detected_total")
                .description("Number of new SLA breach records created")
                .register(meterRegistry);
    }

    /**
     * Idempotently records a detected breach, closes open risk flags, and publishes the
     * {@code SlaBreached} outbox event — all within the caller's {@code REQUIRES_NEW} transaction.
     *
     * @param workOrderId           the work order that breached
     * @param breachType            "RESPONSE" or "RESOLUTION"
     * @param effectiveDeadline     pause-adjusted deadline that was missed
     * @param detectedAt            the instant detection was made (sweep clock)
     * @param overrunMinutes        minutes past the deadline at detection (clamped to ≥ 0)
     * @param pausedMinutesExcluded total paused-clock minutes excluded from the calculation
     * @param openFlags             currently open risk flags to close on breach
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void detect(UUID workOrderId, String breachType,
                Instant effectiveDeadline, Instant detectedAt,
                int overrunMinutes, int pausedMinutesExcluded,
                List<SlaRiskFlag> openFlags) {

        if (repository.existsByWorkOrderIdAndBreachType(workOrderId, breachType)) {
            return; // idempotent — already recorded this breach
        }

        int clampedOverrun = Math.max(0, overrunMinutes);
        if (clampedOverrun != overrunMinutes) {
            log.warn("sla.breach.negative_overrun_clamped: workOrderId={} breachType={} raw={}",
                    workOrderId, breachType, overrunMinutes);
        }

        SlaBreachEntity breach = SlaBreachEntity.detect(
                workOrderId, breachType, effectiveDeadline, detectedAt,
                clampedOverrun, pausedMinutesExcluded);

        try {
            repository.saveAndFlush(breach);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent sweep replica inserted the same record — idempotent, not an error.
            log.debug("sla.breach.concurrent_insert_ignored: workOrderId={} breachType={}",
                    workOrderId, breachType);
            return;
        }

        // Close open AT_RISK and PROJECTED_OVERRUN flags with a "breached" clear reason.
        for (SlaRiskFlag flag : openFlags) {
            if (flag.isOpen()) {
                flag.clear("breached", detectedAt);
                flagRepository.save(flag);
            }
        }

        // Publish SlaBreached outbox event atomically in the same transaction.
        var payload = PiiRedactionUtility.toPayloadMap(new SlaBreachedPayload(
                workOrderId, breach.getId(), breachType,
                effectiveDeadline, detectedAt, clampedOverrun, pausedMinutesExcluded));
        eventPublisher.publish(DomainEvent.of(
                SlaBreachedPayload.EVENT_TYPE,
                SlaBreachedPayload.AGGREGATE_TYPE,
                workOrderId,
                detectedAt, null, null, payload));

        breachDetectedCounter.increment();
        log.info("sla.breach.detected: workOrderId={} breachType={} overrunMinutes={} pausedMinutesExcluded={}",
                workOrderId, breachType, clampedOverrun, pausedMinutesExcluded);
    }

    // ── SlaBreachPort ─────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalise(UUID workOrderId, Instant terminalInstant) {
        List<SlaBreachEntity> unfinalised =
                repository.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId);

        for (SlaBreachEntity breach : unfinalised) {
            long finalMinutes = Math.max(0L,
                    Duration.between(breach.getEffectiveDeadline(), terminalInstant).toMinutes());
            breach.writeFinalOverrun((int) finalMinutes);
            repository.save(breach);
            log.info("sla.breach.finalised: workOrderId={} breachType={} finalOverrunMinutes={}",
                    workOrderId, breach.getBreachType(), finalMinutes);
        }
    }

    // ── SlaBreachAdminService ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SlaBreachDto> listBreaches(String breachType, Boolean unattributed,
                                           Instant from, Instant to,
                                           String sortField, boolean sortAsc,
                                           int page, int size) {
        String resolvedSort = ALLOWED_SORT_FIELDS.contains(sortField) ? sortField : "detectedAt";

        List<SlaBreachEntity> all = repository.findFiltered(breachType, from, to);
        if (Boolean.TRUE.equals(unattributed)) {
            all = all.stream().filter(b -> b.getReasonCode() == null).toList();
        }

        Comparator<SlaBreachEntity> cmp;
        if (resolvedSort.equals("overrunMinutes")) {
            cmp = sortAsc
                    ? Comparator.comparingInt(SlaBreachEntity::getOverrunMinutes)
                                .thenComparing(SlaBreachEntity::getId)
                    : Comparator.<SlaBreachEntity>comparingInt(SlaBreachEntity::getOverrunMinutes).reversed()
                                .thenComparing(SlaBreachEntity::getId);
        } else {
            cmp = sortAsc
                    ? Comparator.<SlaBreachEntity, Instant>comparing(SlaBreachEntity::getDetectedAt)
                                .thenComparing(SlaBreachEntity::getId)
                    : Comparator.<SlaBreachEntity, Instant>comparing(SlaBreachEntity::getDetectedAt).reversed()
                                .thenComparing(SlaBreachEntity::getId);
        }

        int from0 = page * size;
        return all.stream()
                .sorted(cmp)
                .skip(from0)
                .limit(size)
                .map(SlaBreachEntity::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countBreaches(String breachType, Boolean unattributed, Instant from, Instant to) {
        long total = repository.countFiltered(breachType, from, to);
        if (!Boolean.TRUE.equals(unattributed)) {
            return total;
        }
        // Count unattributed: load and filter in-memory (count via findFiltered is already efficient)
        return repository.findFiltered(breachType, from, to).stream()
                .filter(b -> b.getReasonCode() == null).count();
    }

    @Override
    @Transactional
    public SlaBreachDto attributeReason(UUID breachId, SlaBreachReasonCode reasonCode,
                                         String note, UUID attributedBy, Instant attributedAt) {
        SlaBreachEntity breach = repository.findById(breachId)
                .orElseThrow(() -> new NotFoundException("SlaBreachEntity", breachId));
        breach.attributeReason(reasonCode, note, attributedBy, attributedAt);
        return repository.save(breach).toDto();
    }
}
