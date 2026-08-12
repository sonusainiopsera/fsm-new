package com.fieldservice.dispatch.api;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a single eligibility evaluation pass.
 *
 * @param eligible     technician IDs that passed all eligibility gates
 * @param excluded     technicians removed from the pool with machine-readable reason codes
 * @param reachUnknown true when the geographic reach filter was skipped because the
 *                     work order site has no geocoded coordinates — callers must not
 *                     interpret an absent reach exclusion as "within reach"
 */
public record EligibilityResult(
        List<UUID> eligible,
        List<ExcludedCandidate> excluded,
        boolean reachUnknown
) {
    public EligibilityResult {
        eligible = List.copyOf(eligible);
        excluded = List.copyOf(excluded);
    }
}
