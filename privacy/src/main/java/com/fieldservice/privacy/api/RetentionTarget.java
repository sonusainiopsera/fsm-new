package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Strategy interface that binds a data category to its backing storage.
 *
 * <p>One implementation per data category; each is registered as a Spring bean
 * in the owning module's narrow public-api package and discovered by the
 * {@code RetentionPolicyServiceImpl} via {@code List<RetentionTarget>} injection.
 *
 * <p>Implementations must be stateless and safe to call from the worker profile's
 * scheduled sweep.  All queries must be parameterized (no string-concatenated SQL).
 */
public interface RetentionTarget {

    /**
     * The stable category key matching {@code retention_policy.data_category}.
     */
    String getDataCategory();

    /**
     * Returns the count of rows whose anchor-field timestamp is strictly before {@code cutoff}.
     * Must never mutate data.
     */
    long countEligible(Instant cutoff);

    /**
     * Returns the timestamp of the oldest eligible row, or {@code null} if there are none.
     * Must never mutate data.
     */
    Instant oldestEligibleAt(Instant cutoff);

    /**
     * Returns a page of IDs for rows eligible for disposal.
     * Callers page through using repeated calls until an empty list is returned.
     *
     * @param cutoff   rows with anchor-field before this instant are eligible
     * @param pageSize maximum number of IDs per page
     */
    List<UUID> pageEligibleIds(Instant cutoff, int pageSize);

    /**
     * Physically disposes the identified rows and any dependent child data.
     * Called only when {@code app.privacy.purge.execution-enabled=true} and
     * the policy is both ratified and enabled.
     *
     * <p>Must be idempotent: a batch that is partially processed and retried must
     * not fail on already-deleted rows.
     *
     * @param ids row IDs to dispose (guaranteed non-empty, size ≤ configured batch-size)
     */
    void disposeBatch(List<UUID> ids);
}
