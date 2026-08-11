package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.util.List;
import java.util.Set;

/**
 * Value resolved from the transition table for a legal {@link TransitionKey}.
 *
 * @param toState       the state the work order moves into on success
 * @param requiredRoles Spring Security authority strings; the caller must hold at least one
 * @param guardIds      ordered list of guard identifiers evaluated before the transition commits;
 *                      empty in this story — guards are wired in the guards story
 */
public record TransitionDescriptor(
        WorkOrderState toState,
        Set<String> requiredRoles,
        List<String> guardIds
) {}
