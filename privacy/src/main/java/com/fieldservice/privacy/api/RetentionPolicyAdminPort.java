package com.fieldservice.privacy.api;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * Admin operations on the retention policy schedule.
 *
 * <p>Separated from read-model concerns so the controller never references internal
 * entities or repositories directly. All three operations are restricted to
 * {@code PRIVACY_ADMIN} and {@code ADMIN} roles via {@code @PreAuthorize} on the
 * implementation.
 */
public interface RetentionPolicyAdminPort {

    /**
     * Returns a paginated list of all retention policies.
     * Page size is server-enforced to a maximum of 50.
     * Sort is stable: data_category ASC, id ASC.
     */
    PagedResponse<RetentionPolicyView> listPolicies(PageQuery pageQuery, HttpServletRequest request);

    /**
     * Updates the retention configuration for a single policy row.
     *
     * <p>The caller must supply the current {@code version}. A stale version throws
     * {@link com.fieldservice.platform.exception.ConflictException} (→ 409).
     *
     * <p>A period shorter than the 1-year audit-retention floor for
     * {@code AUDIT_RECORDS} throws {@link com.fieldservice.platform.exception.BusinessGuardException}
     * (→ 422).
     */
    RetentionPolicyView updatePolicy(UUID id, UpdateRetentionPolicyRequest request);

    /**
     * Returns a dry-run report for a single policy: the cut-off instant, eligible row count,
     * oldest eligible timestamp and disposal method that would be applied, without mutating any
     * data.
     *
     * <p>If no {@link RetentionTarget} is registered for the category, the eligible count is
     * returned as {@code -1} and the skip reason {@code NO_TARGET_REGISTERED} is included.
     */
    DryRunReport dryRun(UUID id);
}
