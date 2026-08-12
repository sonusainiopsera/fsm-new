package com.fieldservice.workorder.web;

import com.fieldservice.inventory.api.PartShortfall;

import java.util.List;
import java.util.UUID;

/**
 * Advisory assignment warning returned when required parts are not fully available.
 *
 * <p>Warnings are advisory only — they do not block assignment. The dispatcher may
 * acknowledge them via {@link TransitionRequest#acknowledgeWarnings()} and provide
 * a reason that is persisted on the assignment audit record.
 *
 * @param code       warning code (e.g., {@code PARTS_UNAVAILABLE}, {@code PARTS_PARTIALLY_STOCKED})
 * @param message    human-readable description of the availability issue
 * @param shortfalls per-part shortfall detail; may be empty if not applicable
 */
public record AssignmentWarning(String code, String message, List<PartShortfall> shortfalls) {

    public static final String CODE_UNAVAILABLE        = "PARTS_UNAVAILABLE";
    public static final String CODE_PARTIALLY_STOCKED  = "PARTS_PARTIALLY_STOCKED";
    public static final String CODE_COLLECTABLE        = "PARTS_COLLECTABLE";

    public AssignmentWarning {
        if (shortfalls == null) shortfalls = List.of();
        else shortfalls = List.copyOf(shortfalls);
    }
}
