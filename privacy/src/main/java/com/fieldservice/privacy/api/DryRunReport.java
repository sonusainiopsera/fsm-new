package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.List;

/**
 * Result of a dry-run eligibility scan for a single retention policy.
 *
 * <p>The dry-run never mutates any data; it only counts and inspects.
 */
public record DryRunReport(
        String         dataCategory,
        Instant        cutoffInstant,
        long           eligibleCount,
        Instant        oldestEligibleAt,
        DisposalMethod disposalMethod,
        List<String>   skippedReasons
) {}
