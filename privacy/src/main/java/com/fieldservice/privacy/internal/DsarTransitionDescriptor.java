package com.fieldservice.privacy.internal;

import java.util.List;
import java.util.Set;

/**
 * Value resolved from the DSAR transition table for a legal {@link DsarTransitionKey}.
 *
 * @param toState       the state the request moves into on success
 * @param requiredRoles Spring Security authority strings; the caller must hold at least one
 * @param guardIds      ordered list of guard identifiers evaluated before the transition commits
 */
record DsarTransitionDescriptor(
        DsarState toState,
        Set<String> requiredRoles,
        List<String> guardIds
) {}
