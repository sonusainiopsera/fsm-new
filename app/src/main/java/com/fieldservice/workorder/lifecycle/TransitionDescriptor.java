package com.fieldservice.workorder.lifecycle;

import java.util.List;
import java.util.Set;

/**
 * Resolved value from the transition table: the target state, the Spring Security authority
 * strings that may trigger this move, and an ordered list of guard identifiers to evaluate.
 */
public record TransitionDescriptor(
        WorkOrderState toState,
        Set<String> requiredRoles,
        List<String> guardIds) {}
