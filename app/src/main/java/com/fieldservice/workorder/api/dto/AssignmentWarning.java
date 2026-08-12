package com.fieldservice.workorder.api.dto;

import com.fieldservice.inventory.api.PartShortfall;

import java.util.List;

/**
 * Advisory warning included in an {@link AssignmentResponse}.
 *
 * <p>Warnings are informational only — they never gate the assignment.
 * Dispatchers may acknowledge and proceed; the acknowledgement flag and reason
 * are persisted on the assignment audit record.
 */
public record AssignmentWarning(
        String code,
        String message,
        List<PartShortfall> shortfalls
) {
    public AssignmentWarning {
        shortfalls = shortfalls == null ? List.of() : List.copyOf(shortfalls);
    }
}
