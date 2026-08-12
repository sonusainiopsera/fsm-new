package com.fieldservice.sla.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.sla.SlaBreachReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional facade for SLA breach recording, attribution, finalisation, and listing.
 *
 * <p>Public so {@code workorder.application} and {@code sla.web} can call it directly.
 * All internal types ({@link SlaBreachEntity}, {@link SlaBreachRepository}) remain
 * package-private to {@code sla.internal}.
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>Detection is idempotent: a second call with the same {@code (workOrderId, breachType)}
 *       is a no-op (guarded by the unique index and a check-then-insert pattern).</li>
 *   <li>Finalisation is idempotent: {@code final_overrun_minutes} is written once only.</li>
 *   <li>Attribution creates a new Envers revision; the prior reason code is recoverable.</li>
 *   <li>Negative computed overruns are clamped at zero and logged at WARN.</li>
 * </ul>
 */
@Service
public class SlaBreachService {

    private static final Logger log = LoggerFactory.getLogger(SlaBreachService.class);

    private static final String EVENT_TYPE_BREACHED = "SlaBreached";
    private static final String AGGREGATE_TYPE      = "WORK_ORDER";
    private static final String FLAG_CLEAR_REASON   = "BREACHED";

    private final SlaBreachRepository    breachRepo;
    private final SlaRiskFlagRepository  flagRepo;
    private final DomainEventPublisher   eventPublisher;
    private final JdbcTemplate           jdbc;
    private final Clock                  clock;

    public SlaBreachService(SlaBreachRepository breachRepo,
                            SlaRiskFlagRepository flagRepo,
                            DomainEventPublisher eventPublisher,
                            JdbcTemplate jdbc,
                            Clock clock) {
        this.breachRepo     = breachRepo;
        this.flagRepo       = flagRepo;
        this.eventPublisher = eventPublisher;
        this.jdbc           = jdbc;
        this.clock          = clock;
    }

    // ─── Breach recording ────────────────────────────────────────────────────

    /**
     * Records a breach in a single transaction that also closes open at-risk flags and
     * publishes an {@code SlaBreached} outbox event.
     *
     * <p>Idempotent: if a breach of the same type for this work order already exists the
     * method returns immediately without writing any rows or events.
     *
     * <p>Negative computed overruns are clamped at zero and logged at WARN.
     *
     * @param workOrderId           work order that missed its deadline
     * @param breachType            {@code RESPONSE} or {@code RESOLUTION}
     * @param effectiveDeadline     pause-adjusted deadline used for overrun arithmetic
     * @param detectedAt            instant at which the breach was detected
     * @param overrunMinutes        minutes elapsed past effective deadline (≥ 0)
     * @param pausedMinutesExcluded cumulative clock-pausing hold minutes excluded
     */
    @Transactional
    public void recordBreach(UUID workOrderId,
                              String breachType,
                              Instant effectiveDeadline,
                              Instant detectedAt,
                              long overrunMinutes,
                              long pausedMinutesExcluded) {
        if (overrunMinutes < 0) {
            log.warn("sla_breach_negative_overrun workOrderId={} breachType={} overrunMinutes={} — clamping to 0",
                    workOrderId, breachType, overrunMinutes);
            overrunMinutes = 0;
        }

        // Idempotent: skip if already recorded
        if (breachRepo.findByWorkOrderIdAndBreachType(workOrderId, breachType).isPresent()) {
            return;
        }

        SlaBreachEntity breach = SlaBreachEntity.create(
                workOrderId, breachType, effectiveDeadline, detectedAt,
                overrunMinutes, pausedMinutesExcluded);
        try {
            breachRepo.save(breach);
            breachRepo.flush();
        } catch (DataIntegrityViolationException ex) {
            // Concurrent sweep inserted the same breach — idempotent no-op
            log.debug("sla_breach_concurrent_insert workOrderId={} breachType={} — idempotent skip",
                    workOrderId, breachType);
            return;
        }

        // Close any open at-risk flags with reason "BREACHED"
        List<SlaRiskFlagEntity> openFlags = flagRepo.findByWorkOrderIdAndClearedAtIsNull(workOrderId);
        for (SlaRiskFlagEntity flag : openFlags) {
            flag.clear(detectedAt, FLAG_CLEAR_REASON);
            flagRepo.save(flag);
        }

        // Publish outbox event (MANDATORY propagation — must be inside a transaction)
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                EVENT_TYPE_BREACHED,
                AGGREGATE_TYPE,
                workOrderId,
                detectedAt,
                MDC.get("traceId"),
                null,
                new SlaBreachedPayload(workOrderId, breachType, effectiveDeadline,
                        detectedAt, overrunMinutes, pausedMinutesExcluded)));

        log.info("sla_breach_recorded workOrderId={} breachType={} overrunMinutes={}",
                workOrderId, breachType, overrunMinutes);
    }

    // ─── Finalisation ────────────────────────────────────────────────────────

    /**
     * Writes {@code final_overrun_minutes} for all unfinalised breaches of a work order.
     *
     * <p>Idempotent: breaches already finalised (non-null {@code final_overrun_minutes})
     * are skipped. Safe to call on transition replay.
     *
     * @param workOrderId    work order transitioning to a terminal state
     * @param closureInstant instant of closure or cancellation
     */
    @Transactional
    public void finalise(UUID workOrderId, Instant closureInstant) {
        List<SlaBreachEntity> unfinalised =
                breachRepo.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId);

        for (SlaBreachEntity breach : unfinalised) {
            long finalOverrun = Math.max(0,
                    Duration.between(breach.getEffectiveDeadline(), closureInstant).toMinutes());
            if (finalOverrun < breach.getOverrunMinutes()) {
                // Anomaly: closure is before detection — keep detection overrun as final
                log.warn("sla_breach_finalise_anomaly workOrderId={} breachType={} — " +
                                "closureInstant={} is before detectedAt={}, using detection overrun",
                        workOrderId, breach.getBreachType(), closureInstant, breach.getDetectedAt());
                finalOverrun = breach.getOverrunMinutes();
            }
            breach.finalise(finalOverrun);
            breachRepo.save(breach);
        }
    }

    // ─── Attribution ─────────────────────────────────────────────────────────

    /**
     * Attributes a reason code to a breach (or re-attributes — creates a new Envers revision).
     *
     * <p>If the breach does not exist the method returns an empty Optional; the controller
     * must treat empty as 403 (no existence disclosure).
     *
     * @param breachId     UUID of the breach to attribute
     * @param reasonCode   controlled-vocabulary reason code
     * @param reasonNote   optional free-text note (length-capped at 500 chars by the entity)
     * @param attributedBy user UUID performing the attribution
     * @return the updated breach, or empty if not found
     */
    @Transactional
    public Optional<SlaBreachEntity> attribute(UUID breachId,
                                                SlaBreachReasonCode reasonCode,
                                                String reasonNote,
                                                UUID attributedBy) {
        Optional<SlaBreachEntity> opt = breachRepo.findById(breachId);
        opt.ifPresent(breach -> {
            breach.attribute(reasonCode, reasonNote, attributedBy, clock.instant());
            breachRepo.save(breach);
        });
        return opt;
    }

    // ─── Paginated listing ───────────────────────────────────────────────────

    /**
     * Returns a page of breaches filtered by optional criteria.
     *
     * <p>Uses JdbcTemplate with a JOIN to {@code work_order} for priority filtering;
     * all other filters apply to {@code sla_breach} directly. Sort is fixed to
     * {@code detected_at DESC, id ASC} for deterministic ordering.
     *
     * @param breachType      optional RESPONSE / RESOLUTION filter
     * @param unattributedOnly if true, only records with null reason_code
     * @param reasonCode      optional specific reason code
     * @param priority        optional work order priority (P1 / P2 / P3 / P4)
     * @param from            optional lower bound on detected_at (inclusive)
     * @param to              optional upper bound on detected_at (inclusive)
     * @param pageable        page and size (caller must cap size to 50)
     * @return page of matching breach entities
     */
    @Transactional(readOnly = true)
    public Page<SlaBreachEntity> list(String breachType, boolean unattributedOnly,
                                       String reasonCode, String priority,
                                       Instant from, Instant to, Pageable pageable) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");

        if (breachType != null) {
            where.append(" AND b.breach_type = ? ");
            params.add(breachType);
        }
        if (unattributedOnly) {
            where.append(" AND b.reason_code IS NULL ");
        }
        if (reasonCode != null) {
            where.append(" AND b.reason_code = ? ");
            params.add(reasonCode);
        }
        if (priority != null) {
            where.append(" AND wo.priority = ? ");
            params.add(priority);
        }
        if (from != null) {
            where.append(" AND b.detected_at >= ? ");
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND b.detected_at <= ? ");
            params.add(java.sql.Timestamp.from(to));
        }

        String baseQuery = """
                FROM sla_breach b
                JOIN work_order wo ON wo.id = b.work_order_id
                """ + where;

        long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + baseQuery, params.toArray(), Long.class);

        String dataQuery = "SELECT b.* " + baseQuery
                + " ORDER BY b.detected_at DESC, b.id ASC "
                + " LIMIT ? OFFSET ? ";
        params.add(pageable.getPageSize());
        params.add(pageable.getOffset());

        List<SlaBreachEntity> rows = jdbc.query(dataQuery, params.toArray(), BREACH_ROW_MAPPER);
        return new PageImpl<>(rows, pageable, total == null ? 0L : total);
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private static final RowMapper<SlaBreachEntity> BREACH_ROW_MAPPER = (rs, rowNum) -> {
        SlaBreachEntity e = SlaBreachEntity.create(
                UUID.fromString(rs.getString("work_order_id")),
                rs.getString("breach_type"),
                rs.getTimestamp("effective_deadline").toInstant(),
                rs.getTimestamp("detected_at").toInstant(),
                rs.getLong("overrun_minutes"),
                rs.getLong("paused_minutes_excluded"));
        setId(e, UUID.fromString(rs.getString("id")));
        setFinalOverrunMinutes(e, rs.getObject("final_overrun_minutes", Long.class));
        String rc = rs.getString("reason_code");
        if (rc != null) {
            String note = rs.getString("reason_note");
            String attrBy = rs.getString("attributed_by");
            java.sql.Timestamp attrAt = rs.getTimestamp("attributed_at");
            e.attribute(
                    SlaBreachReasonCode.valueOf(rc),
                    note,
                    attrBy != null ? UUID.fromString(attrBy) : null,
                    attrAt != null ? attrAt.toInstant() : null);
        }
        return e;
    };

    // Reflective helpers to set immutable fields for the JDBC row mapper.
    // Needed because the id and final_overrun_minutes fields have no public setters
    // (by design — they're set only at construction or finalisation time).
    private static void setId(SlaBreachEntity e, UUID id) {
        try {
            var f = SlaBreachEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot set SlaBreachEntity.id", ex);
        }
    }

    private static void setFinalOverrunMinutes(SlaBreachEntity e, Long value) {
        if (value == null) return;
        try {
            var f = SlaBreachEntity.class.getDeclaredField("finalOverrunMinutes");
            f.setAccessible(true);
            f.set(e, value);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot set SlaBreachEntity.finalOverrunMinutes", ex);
        }
    }

    // ─── Outbox payload ──────────────────────────────────────────────────────

    record SlaBreachedPayload(
            UUID    workOrderId,
            String  breachType,
            Instant effectiveDeadline,
            Instant detectedAt,
            long    overrunMinutes,
            long    pausedMinutesExcluded
    ) {}
}
