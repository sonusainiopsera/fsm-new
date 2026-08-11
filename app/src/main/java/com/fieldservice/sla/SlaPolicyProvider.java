package com.fieldservice.sla;

import java.time.Instant;

/**
 * Public port for resolving the active SLA policy for a priority at a given instant.
 *
 * <p>Implementations must select the single active row where:
 * <ul>
 *   <li>{@code priority} matches</li>
 *   <li>{@code effective_from <= at}</li>
 *   <li>{@code effective_to IS NULL OR effective_to > at}</li>
 *   <li>{@code active = true}</li>
 * </ul>
 * If multiple rows qualify, the one with the latest {@code effective_from} wins.
 * If none qualify, implementations throw {@link SlaPolicyUnavailableException}.
 */
public interface SlaPolicyProvider {

    /**
     * Resolves the active policy for {@code priority} at instant {@code at}.
     *
     * @throws SlaPolicyUnavailableException if no active policy row resolves
     */
    SlaPolicy resolveActivePolicy(String priority, Instant at);
}
