package com.fieldservice.workorder.lifecycle;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.*;
import static com.fieldservice.workorder.lifecycle.WorkOrderState.*;

/**
 * Immutable declarative transition table. This is the single source of truth for every
 * lifecycle rule; no other production class may branch on {@link WorkOrderState}.
 *
 * <p>Roles are Spring Security authority strings so method security and the table share one vocabulary.
 */
final class WorkOrderTransitionTable {

    private static final Map<TransitionKey, TransitionDescriptor> TABLE =
            Collections.unmodifiableMap(Map.ofEntries(
                    // ---- NEW -------------------------------------------------------
                    entry(NEW, ASSIGN, ASSIGNED,
                            Set.of("DISPATCHER", "ADMIN"),
                            List.of("certification.current")),
                    entry(NEW, CANCEL, CANCELLED,
                            Set.of("DISPATCHER", "ADMIN", "MANAGER"), List.of()),

                    // ---- ASSIGNED --------------------------------------------------
                    entry(ASSIGNED, UNASSIGN, NEW,
                            Set.of("DISPATCHER", "ADMIN"), List.of()),
                    entry(ASSIGNED, DEPART, EN_ROUTE,
                            Set.of("TECHNICIAN", "DISPATCHER", "ADMIN"), List.of()),
                    entry(ASSIGNED, CANCEL, CANCELLED,
                            Set.of("DISPATCHER", "ADMIN", "MANAGER"), List.of()),

                    // ---- EN_ROUTE --------------------------------------------------
                    entry(EN_ROUTE, START, IN_PROGRESS,
                            Set.of("TECHNICIAN", "DISPATCHER", "ADMIN"), List.of()),
                    entry(EN_ROUTE, CANCEL, CANCELLED,
                            Set.of("DISPATCHER", "ADMIN", "MANAGER"), List.of()),

                    // ---- IN_PROGRESS -----------------------------------------------
                    entry(IN_PROGRESS, HOLD, ON_HOLD,
                            Set.of("TECHNICIAN", "DISPATCHER", "ADMIN"),
                            List.of("hold.reason.required")),
                    entry(IN_PROGRESS, COMPLETE, COMPLETED,
                            Set.of("TECHNICIAN", "DISPATCHER", "ADMIN"),
                            List.of("labour.time.recorded")),
                    entry(IN_PROGRESS, CANCEL, CANCELLED,
                            Set.of("DISPATCHER", "ADMIN", "MANAGER"), List.of()),

                    // ---- ON_HOLD ---------------------------------------------------
                    entry(ON_HOLD, RESUME, IN_PROGRESS,
                            Set.of("TECHNICIAN", "DISPATCHER", "ADMIN"), List.of()),
                    entry(ON_HOLD, CANCEL, CANCELLED,
                            Set.of("DISPATCHER", "ADMIN", "MANAGER"), List.of()),

                    // ---- COMPLETED -------------------------------------------------
                    entry(COMPLETED, CLOSE, CLOSED,
                            Set.of("DISPATCHER", "ADMIN", "MANAGER"),
                            List.of("parts.reconciled"))
                    // CLOSED and CANCELLED are terminal — zero outbound entries
            ));

    static Optional<TransitionDescriptor> resolve(WorkOrderState fromState, WorkOrderEvent event) {
        return Optional.ofNullable(TABLE.get(new TransitionKey(fromState, event)));
    }

    static Set<WorkOrderEvent> legalEventsFrom(WorkOrderState state) {
        return TABLE.keySet().stream()
                .filter(k -> k.fromState() == state)
                .map(TransitionKey::event)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Exposed for table-immutability tests only. */
    static Map<TransitionKey, TransitionDescriptor> rawTable() {
        return TABLE;
    }

    private WorkOrderTransitionTable() {}

    private static Map.Entry<TransitionKey, TransitionDescriptor> entry(
            WorkOrderState from, WorkOrderEvent event,
            WorkOrderState to, Set<String> roles, List<String> guardIds) {
        return Map.entry(new TransitionKey(from, event),
                new TransitionDescriptor(to, Set.copyOf(roles), List.copyOf(guardIds)));
    }
}
