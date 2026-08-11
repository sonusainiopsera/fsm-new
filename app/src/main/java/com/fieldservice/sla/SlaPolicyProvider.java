package com.fieldservice.sla;

import com.fieldservice.sla.domain.SlaPolicy;

import java.time.Instant;
import java.util.Optional;

/**
 * Public interface for resolving the active SLA policy for a priority at a given instant.
 * Implementations are expected to be cached; callers may invoke this inside a transaction.
 */
public interface SlaPolicyProvider {

    /**
     * Resolves the active policy for the given priority at the given instant.
     *
     * @return the active policy, or empty if none is defined (callers must treat empty as 422)
     */
    Optional<SlaPolicy> resolve(String priority, Instant at);
}
