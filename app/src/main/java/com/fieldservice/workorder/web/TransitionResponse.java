package com.fieldservice.workorder.web;

import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderState;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Response body for a successful work order state transition. */
public record TransitionResponse(
        UUID workOrderId,
        String fromState,
        String toState,
        int version,
        Set<String> legalNextEvents,
        Instant occurredAt,
        List<AssignmentWarning> warnings) {

    public static TransitionResponse of(UUID workOrderId,
                                         WorkOrderState fromState,
                                         WorkOrderState toState,
                                         int version,
                                         Set<WorkOrderEvent> legalNextEvents,
                                         Instant occurredAt) {
        return new TransitionResponse(
                workOrderId,
                fromState.name(),
                toState.name(),
                version,
                legalNextEvents.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                occurredAt,
                List.of());
    }

    public static TransitionResponse ofWithWarnings(UUID workOrderId,
                                                     WorkOrderState fromState,
                                                     WorkOrderState toState,
                                                     int version,
                                                     Set<WorkOrderEvent> legalNextEvents,
                                                     Instant occurredAt,
                                                     List<AssignmentWarning> warnings) {
        return new TransitionResponse(
                workOrderId,
                fromState.name(),
                toState.name(),
                version,
                legalNextEvents.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                occurredAt,
                warnings == null ? List.of() : List.copyOf(warnings));
    }
}
