package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI for per-module data retention targets.
 *
 * <p>Each bounded context that owns a retention-policy category implements this interface
 * and registers it as a Spring bean. The {@code PurgeSweepJob} discovers all implementations
 * at startup and dispatches to the one whose {@link #getDataCategory()} matches each
 * enabled policy row.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #countEligible} and {@link #pageEligibleIds} must be read-only and
 *       must not mutate data (used by the dry-run path).</li>
 *   <li>{@link #disposeBatch} performs genuine physical deletion or delegates to
 *       cryptographic erasure; soft-delete alone is never acceptable.</li>
 *   <li>Implementations must be idempotent: calling {@link #disposeBatch} twice
 *       with the same IDs must not fail.</li>
 * </ul>
 */
public interface RetentionTarget {

    /**
     * The {@code data_category} value in {@code retention_policy} that this target handles.
     * Must match exactly — case-sensitive.
     */
    String getDataCategory();

    /**
     * Returns the number of rows whose anchor timestamp is older than {@code cutoff}.
     * Must be read-only; used by the dry-run path.
     */
    long countEligible(Instant cutoff);

    /**
     * Returns at most {@code pageSize} eligible row IDs, ordered by anchor timestamp ascending.
     * Must be read-only; used by the dry-run path and the sweep pagination loop.
     */
    List<UUID> pageEligibleIds(Instant cutoff, int pageSize);

    /**
     * Physically disposes the given rows and any dependent child rows or object-storage objects.
     * Implementation must be idempotent: rows already deleted must be silently skipped.
     */
    void disposeBatch(List<UUID> ids);

    /**
     * Returns the oldest anchor timestamp among eligible rows, or empty if there are no eligible rows.
     * Default implementation returns empty (optional optimisation for the dry-run report).
     */
    default Optional<Instant> oldestEligibleAt(Instant cutoff) {
        return Optional.empty();
    }
}
