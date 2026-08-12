package com.fieldservice.sla;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public read/attribution service for SLA breach records.
 *
 * <p>Implemented by the internal {@code SlaBreachService}.
 * Used by {@code SlaBreachController} in the {@code sla.web} package.
 */
public interface SlaBreachAdminService {

    /**
     * Returns a page of breach records matching the given filter criteria.
     *
     * @param breachType      filter by breach type ("RESPONSE" or "RESOLUTION"), or {@code null} for all
     * @param unattributed    when {@code true}, only return records without a reason code
     * @param from            lower bound on detected_at (inclusive), or {@code null}
     * @param to              upper bound on detected_at (inclusive), or {@code null}
     * @param sortField       allow-listed sort field name; defaults to "detectedAt" if null/invalid
     * @param sortAsc         sort direction
     * @param page            zero-based page index
     * @param size            page size (caller must clamp to ≤ 50)
     * @return matching breach DTOs
     */
    List<SlaBreachDto> listBreaches(String breachType, Boolean unattributed,
                                    Instant from, Instant to,
                                    String sortField, boolean sortAsc,
                                    int page, int size);

    /**
     * Total count matching the same filter (for pagination links).
     */
    long countBreaches(String breachType, Boolean unattributed, Instant from, Instant to);

    /**
     * Attributes a reason code to a breach record.
     *
     * @param breachId       the breach to attribute
     * @param reasonCode     controlled-vocabulary reason code
     * @param note           optional free-text note (max 500 chars)
     * @param attributedBy   actor performing the attribution
     * @param attributedAt   instant of attribution
     * @return updated breach DTO
     * @throws com.fieldservice.platform.exception.NotFoundException if breach not found
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException on concurrent update
     */
    SlaBreachDto attributeReason(UUID breachId, SlaBreachReasonCode reasonCode,
                                  String note, UUID attributedBy, Instant attributedAt);
}
