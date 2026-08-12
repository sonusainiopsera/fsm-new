package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarState;

import java.util.List;
import java.util.Set;

/**
 * Resolved value from the DSAR transition table: the target state,
 * the roles that may trigger this transition, and ordered guard ids.
 */
record DsarTransitionDescriptor(
        DsarState    toState,
        Set<String>  requiredRoles,
        List<String> guardIds
) {}
