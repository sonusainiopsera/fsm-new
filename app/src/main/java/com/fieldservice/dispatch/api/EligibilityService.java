package com.fieldservice.dispatch.api;

/**
 * Public port for the dispatch eligibility gate.
 *
 * <p>This is the only type in {@code dispatch.api} that external modules may reference
 * for dispatch eligibility. No internal dispatch types cross this boundary.
 *
 * <p>The gate never fails open: a thrown {@link EligibilityDataException} means certification
 * or availability data could not be loaded, and the caller must not proceed with an
 * unfiltered candidate list.
 */
public interface EligibilityService {

    /**
     * Evaluates all active technicians against {@code requirements} and returns
     * the eligible set plus structured exclusion reasons for every removed candidate.
     *
     * @param requirements  the work order's certification, availability, and geo constraints
     * @return never {@code null}; an empty eligible list is a valid, non-error outcome
     * @throws EligibilityDataException when certification or availability data cannot be loaded
     */
    EligibilityResult evaluate(WorkOrderRequirements requirements);
}
