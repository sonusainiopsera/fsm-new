package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.DisposalMethod;
import com.fieldservice.privacy.api.DryRunReport;

import java.time.Instant;
import java.util.List;

/**
 * JSON response body for POST /api/v1/privacy/retention-policies/{id}/dry-run.
 */
public record DryRunResponse(
        String         dataCategory,
        Instant        cutoffInstant,
        long           eligibleCount,
        Instant        oldestEligibleAt,
        DisposalMethod disposalMethod,
        List<String>   skippedReasons
) {
    static DryRunResponse from(DryRunReport report) {
        return new DryRunResponse(
                report.dataCategory(),
                report.cutoffInstant(),
                report.eligibleCount(),
                report.oldestEligibleAt(),
                report.disposalMethod(),
                report.skippedReasons());
    }
}
