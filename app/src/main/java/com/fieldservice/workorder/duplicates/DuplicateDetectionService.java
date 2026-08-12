package com.fieldservice.workorder.duplicates;

import com.fieldservice.domain.workorder.WorkOrder;

import java.util.List;

/**
 * Advisory duplicate detection: returns up to five open work orders that match
 * the incoming work order by deterministic, explainable rules.
 *
 * <p>Detection must never block creation. Callers must wrap invocations in a try/catch
 * and degrade to an empty list on any exception.
 */
public interface DuplicateDetectionService {

    /**
     * Returns candidate duplicates for the given work order, ranked by similarity.
     *
     * <p>Candidates are open work orders for the same customer, within the configured
     * recent window, matching by asset, site, or fault-signature overlap.
     *
     * @param workOrder the newly-created or existing work order to find duplicates for
     * @return up to five candidates, ranked by asset > site > signature overlap; never null
     */
    List<DuplicateCandidate> detect(WorkOrder workOrder);
}
