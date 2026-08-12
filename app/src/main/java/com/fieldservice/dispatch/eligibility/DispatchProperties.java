package com.fieldservice.dispatch.eligibility;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the dispatch eligibility module.
 *
 * <p>Bound from the {@code dispatch.eligibility} prefix in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "dispatch.eligibility")
class DispatchProperties {

    /** Maximum number of technician candidates loaded per evaluation request. */
    private int candidatePageSize = 200;

    public int getCandidatePageSize()                       { return candidatePageSize; }
    public void setCandidatePageSize(int candidatePageSize) { this.candidatePageSize = candidatePageSize; }
}
