package com.fieldservice.privacy.api;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Result of a dry-run request against a single retention policy.
 *
 * <p>No data is mutated during a dry-run. The caller may use this to review
 * the disposal scope before enabling a policy for live execution.
 *
 * @param dataCategory      the {@code data_category} of the policy
 * @param cutoffInstant     the computed cut-off instant — rows with anchor timestamp
 *                          older than this would be eligible for disposal
 * @param eligibleCount     number of rows eligible for disposal at the current cut-off
 * @param oldestEligibleAt  anchor timestamp of the oldest eligible row; {@code null}
 *                          if the target does not support this query or there are no
 *                          eligible rows
 * @param disposalMethod    the disposal method that would be applied
 * @param skippedReasons    reasons why the run would be skipped (e.g. legal hold,
 *                          unratified, no target registered)
 */
public record DryRunReport(
        String dataCategory,
        Instant cutoffInstant,
        long eligibleCount,
        @Nullable Instant oldestEligibleAt,
        String disposalMethod,
        List<String> skippedReasons
) {}
