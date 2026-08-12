package com.fieldservice.privacy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service contract for the retention policy registry.
 *
 * <p>All methods requiring a PRIVACY_ADMIN or ADMIN role have {@code @PreAuthorize}
 * on the controller methods that call them; service-layer callers (scheduled sweep)
 * use SYSTEM principal and bypass method security.
 */
public interface RetentionPolicyService {

    /**
     * Returns a paginated, sortable list of retention policy rows.
     *
     * @param pageable pagination and sort (size already clamped to ≤ 50 by the controller)
     */
    Page<RetentionPolicyView> listPolicies(Pageable pageable);

    /**
     * Returns a single policy row by id.
     *
     * @throws com.fieldservice.platform.api.exception.NotFoundException if not found
     */
    RetentionPolicyView getPolicy(UUID id);

    /**
     * Updates a retention policy row (optimistic locking via version).
     *
     * @param id               policy row primary key
     * @param periodValue      new retention period value (must be > 0)
     * @param periodUnit       new retention period unit
     * @param disposalMethod   new disposal method
     * @param legalHold        whether a legal hold is in effect
     * @param ratified         whether the DPO has ratified this period
     * @param enabled          whether the sweep should process this category
     * @param notes            updated notes (may be null)
     * @param expectedVersion  client optimistic-lock version; 409 if stale
     * @param updatedBy        actor identifier for the audit trail
     * @throws com.fieldservice.platform.api.exception.NotFoundException if not found
     * @throws com.fieldservice.platform.api.exception.ConflictException if version mismatch
     * @throws AuditFloorViolationException if the period would breach the one-year audit floor
     */
    RetentionPolicyView updatePolicy(UUID id,
                                      int periodValue,
                                      RetentionPeriodUnit periodUnit,
                                      DisposalMethod disposalMethod,
                                      boolean legalHold,
                                      boolean ratified,
                                      boolean enabled,
                                      String notes,
                                      int expectedVersion,
                                      String updatedBy);

    /**
     * Performs a dry-run eligibility scan for the given policy.
     *
     * <p>Resolves the cut-off instant from the policy's period and the current clock,
     * delegates {@code countEligible} to the matching {@link RetentionTarget},
     * and returns the report without mutating any data.
     *
     * @param policyId retention_policy row id
     * @throws com.fieldservice.platform.api.exception.NotFoundException if not found
     */
    DryRunReport dryRun(UUID policyId);

    /**
     * Thrown when an update would set an audit-category retention period
     * below the mandatory one-year floor.
     */
    class AuditFloorViolationException extends RuntimeException {
        public AuditFloorViolationException(String message) {
            super(message);
        }
    }
}
