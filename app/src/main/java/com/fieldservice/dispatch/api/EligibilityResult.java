package com.fieldservice.dispatch.api;

import java.util.List;
import java.util.UUID;

/**
 * Output of one eligibility evaluation pass.
 *
 * @param eligibleTechnicianIds  ordered list of technician IDs that passed all rules
 * @param excluded               every removed candidate with its primary exclusion reason
 * @param reachUnknownCount      candidates whose home-base coordinates were absent —
 *                               the geo check was skipped for them (not excluded for distance)
 */
public record EligibilityResult(
        List<UUID> eligibleTechnicianIds,
        List<ExcludedCandidate> excluded,
        int reachUnknownCount
) {
    public EligibilityResult {
        eligibleTechnicianIds = List.copyOf(eligibleTechnicianIds);
        excluded              = List.copyOf(excluded);
    }
}
