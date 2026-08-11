package com.fieldservice.analytics.internal.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processes work-order closure events to populate the analytics closure projection and
 * link repeat visits.
 *
 * <p>Runs within the outbox-poller transaction ({@code Propagation.MANDATORY}).
 * All writes are idempotent: the unique constraints on {@code analytics_closure_projection}
 * and {@code repeat_visit_link} ensure replayed events produce no duplicate rows.
 *
 * <p>Fault-key derivation: {@code fault_code} when present; otherwise
 * {@code UPPER(fault_category)}. Work orders with neither field are classified as
 * UNCLASSIFIABLE and stored with a NULL {@code fault_key} — they are excluded from
 * the numerator and denominator of the first-time fix rate.
 *
 * <p>Repeat-visit window: a later closure is a repeat if it shares the same asset and
 * fault key AND the gap between closures is strictly less than 30 days. Exactly 30 days
 * falls outside the window and does not break first-time fix.
 */
@Component
public class RepeatVisitLinker {

    private static final Logger log = LoggerFactory.getLogger(RepeatVisitLinker.class);

    private static final Set<String> CLOSURE_STATES = Set.of("CLOSED", "COMPLETED");

    private final ClosureProjectionRepository closureRepo;
    private final RepeatVisitLinkRepository   linkRepo;
    private final CohortMaturityResolver      maturityResolver;
    private final JdbcTemplate                jdbc;
    private final ObjectMapper                objectMapper;

    public RepeatVisitLinker(ClosureProjectionRepository closureRepo,
                             RepeatVisitLinkRepository   linkRepo,
                             CohortMaturityResolver      maturityResolver,
                             JdbcTemplate                jdbc,
                             ObjectMapper                objectMapper) {
        this.closureRepo      = closureRepo;
        this.linkRepo         = linkRepo;
        this.maturityResolver = maturityResolver;
        this.jdbc             = jdbc;
        this.objectMapper     = objectMapper;
    }

    /**
     * Processes a {@code WORK_ORDER_TRANSITION} event.
     * No-ops for non-closure transitions, idempotent on replay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void processEvent(DomainEvent event) {
        if (!"WORK_ORDER_TRANSITION".equals(event.eventType())) {
            return;
        }
        if (!isClosureTransition(event.payload())) {
            return;
        }

        UUID workOrderId = event.aggregateId();
        Instant closedAt = event.occurredAt();

        // Look up work order fault and asset details
        Map<String, Object> woRow = fetchWorkOrderDetails(workOrderId);
        if (woRow == null) {
            log.warn("analytics_linker_wo_not_found work_order_id={}", workOrderId);
            return;
        }

        UUID   assetId       = (UUID)   woRow.get("asset_id");
        String faultCode     = (String) woRow.get("fault_code");
        String faultCategory = (String) woRow.get("fault_category");
        String assetCategory = (String) woRow.get("asset_category");

        String faultKey = deriveFaultKey(faultCode, faultCategory);

        String maturity  = maturityResolver.resolve(closedAt);
        Instant maturedAt = maturityResolver.maturedAt(closedAt);

        // Idempotent upsert into closure projection
        ClosureProjectionEntity projection = closureRepo.findByWorkOrderId(workOrderId)
                .orElseGet(() -> {
                    ClosureProjectionEntity e = new ClosureProjectionEntity(
                            UuidV7.generate(), workOrderId, assetId, assetCategory,
                            faultKey, closedAt, maturity, maturedAt);
                    return closureRepo.save(e);
                });

        // Only attempt linking for classifiable work orders
        if (assetId == null || faultKey == null) {
            log.debug("analytics_linker_unclassifiable work_order_id={}", workOrderId);
            return;
        }

        // Find prior closures within the 30-day window
        Instant windowStart = closedAt.minus(
                java.time.Duration.ofDays(CohortMaturityResolver.WINDOW_DAYS).minusNanos(1));
        List<ClosureProjectionEntity> priors = closureRepo.findPriorClosures(
                assetId, faultKey, windowStart, closedAt);

        if (priors.isEmpty()) {
            return;
        }

        // Link to the immediate predecessor (closest prior closure within window)
        ClosureProjectionEntity predecessor = priors.get(0);

        boolean alreadyLinked = linkRepo.existsByEarlierWorkOrderIdAndLaterWorkOrderId(
                predecessor.getWorkOrderId(), workOrderId);
        if (!alreadyLinked) {
            int days = maturityResolver.daysBetween(predecessor.getClosedAt(), closedAt);
            RepeatVisitLinkEntity link = new RepeatVisitLinkEntity(
                    UuidV7.generate(),
                    predecessor.getWorkOrderId(),
                    workOrderId,
                    assetId,
                    faultKey,
                    days,
                    closedAt);
            linkRepo.save(link);

            // Mark predecessor as not-first-time-fix (chain: each earlier WO is marked)
            if (predecessor.isFirstTimeFix()) {
                predecessor.setFirstTimeFix(false);
                closureRepo.save(predecessor);
                log.debug("analytics_linker_marked_not_ftf work_order_id={} predecessor_id={}",
                        workOrderId, predecessor.getWorkOrderId());
            }
        }

        log.debug("analytics_linker_linked work_order_id={} predecessor={} days={}",
                workOrderId, predecessor.getWorkOrderId(),
                maturityResolver.daysBetween(predecessor.getClosedAt(), closedAt));
    }

    private boolean isClosureTransition(Object payload) {
        if (payload == null) return false;
        try {
            String json = payload instanceof String s ? s : objectMapper.writeValueAsString(payload);
            JsonNode node = objectMapper.readTree(json);
            JsonNode toState = node.get("toState");
            return toState != null && CLOSURE_STATES.contains(toState.asText());
        } catch (Exception e) {
            log.warn("analytics_linker_payload_parse_error: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, Object> fetchWorkOrderDetails(UUID workOrderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT wo.asset_id,
                       wo.fault_code,
                       wo.fault_category,
                       a.category AS asset_category
                  FROM work_order wo
                  LEFT JOIN asset a ON a.id = wo.asset_id
                 WHERE wo.id = ?
                """, workOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** fault_code if present, otherwise UPPER(fault_category), or null if both absent. */
    static String deriveFaultKey(String faultCode, String faultCategory) {
        if (faultCode != null && !faultCode.isBlank()) {
            return faultCode.trim();
        }
        if (faultCategory != null && !faultCategory.isBlank()) {
            return faultCategory.trim().toUpperCase();
        }
        return null;
    }
}
