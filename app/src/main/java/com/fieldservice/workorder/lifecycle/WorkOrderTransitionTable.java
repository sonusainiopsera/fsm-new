package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.security.Role;
import com.fieldservice.workorder.lifecycle.guards.CertificationCurrencyGuard;
import com.fieldservice.workorder.lifecycle.guards.HoldReasonRequiredGuard;
import com.fieldservice.workorder.lifecycle.guards.LabourTimeRecordedGuard;
import com.fieldservice.workorder.lifecycle.guards.PartsReconciledGuard;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.fieldservice.domain.workorder.WorkOrderState.ASSIGNED;
import static com.fieldservice.domain.workorder.WorkOrderState.CANCELLED;
import static com.fieldservice.domain.workorder.WorkOrderState.CLOSED;
import static com.fieldservice.domain.workorder.WorkOrderState.COMPLETED;
import static com.fieldservice.domain.workorder.WorkOrderState.EN_ROUTE;
import static com.fieldservice.domain.workorder.WorkOrderState.IN_PROGRESS;
import static com.fieldservice.domain.workorder.WorkOrderState.NEW;
import static com.fieldservice.domain.workorder.WorkOrderState.ON_HOLD;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.ASSIGN;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.CANCEL;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.CLOSE;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.COMPLETE;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.DEPART;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.HOLD;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.RESUME;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.START;

/**
 * Single, immutable source of truth for all work order lifecycle rules.
 *
 * <p>The table is built once at class load from {@link Map#ofEntries} and wrapped in
 * {@link Collections#unmodifiableMap}. No external code can modify it; any mutation attempt
 * throws {@link UnsupportedOperationException}.
 *
 * <p>Cancellation reachability is resolved by ADR-0007: both EN_ROUTE → CANCELLED and
 * ON_HOLD → CANCELLED are permitted. See {@code docs/adr/adr-0007-work-order-cancellation-reachability.md}.
 *
 * <p>Terminal states CLOSED and CANCELLED have zero outbound entries; no event — including
 * ADMIN-held events — produces a transition out of a terminal state.
 */
public final class WorkOrderTransitionTable {

    private static final Map<TransitionKey, TransitionDescriptor> TABLE =
            Collections.unmodifiableMap(Map.ofEntries(

                    // ── Happy path ─────────────────────────────────────────────
                    row(NEW,         ASSIGN,   ASSIGNED,    roles(Role.DISPATCHER, Role.ADMIN),
                            CertificationCurrencyGuard.GUARD_ID),
                    row(ASSIGNED,    DEPART,   EN_ROUTE,    roles(Role.TECHNICIAN, Role.DISPATCHER, Role.ADMIN)),
                    row(ASSIGNED,    START,    IN_PROGRESS, roles(Role.TECHNICIAN, Role.DISPATCHER, Role.ADMIN)),
                    row(EN_ROUTE,    START,    IN_PROGRESS, roles(Role.TECHNICIAN, Role.DISPATCHER, Role.ADMIN)),
                    row(IN_PROGRESS, HOLD,     ON_HOLD,     roles(Role.TECHNICIAN, Role.DISPATCHER, Role.ADMIN),
                            HoldReasonRequiredGuard.GUARD_ID),
                    row(ON_HOLD,     RESUME,   IN_PROGRESS, roles(Role.TECHNICIAN, Role.DISPATCHER, Role.ADMIN)),
                    row(IN_PROGRESS, COMPLETE, COMPLETED,   roles(Role.TECHNICIAN, Role.DISPATCHER, Role.ADMIN),
                            LabourTimeRecordedGuard.GUARD_ID),
                    row(COMPLETED,   CLOSE,    CLOSED,      roles(Role.DISPATCHER, Role.ADMIN),
                            PartsReconciledGuard.GUARD_ID),

                    // ── Cancellation (per ADR-0007) ─────────────────────────────
                    row(NEW,         CANCEL,   CANCELLED,   roles(Role.DISPATCHER, Role.ADMIN)),
                    row(ASSIGNED,    CANCEL,   CANCELLED,   roles(Role.DISPATCHER, Role.ADMIN)),
                    row(EN_ROUTE,    CANCEL,   CANCELLED,   roles(Role.DISPATCHER, Role.ADMIN)),
                    row(IN_PROGRESS, CANCEL,   CANCELLED,   roles(Role.DISPATCHER, Role.ADMIN)),
                    row(ON_HOLD,     CANCEL,   CANCELLED,   roles(Role.DISPATCHER, Role.ADMIN))
            ));

    private WorkOrderTransitionTable() {}

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Resolve the descriptor for a (fromState, event) pair.
     *
     * @return non-empty if the transition is legal; empty if it is not defined in the table
     */
    public static Optional<TransitionDescriptor> resolve(WorkOrderState fromState, WorkOrderEvent event) {
        return Optional.ofNullable(TABLE.get(new TransitionKey(fromState, event)));
    }

    /**
     * Return the set of events that have a legal outbound entry from {@code state}.
     * Terminal states return an empty set.
     */
    public static Set<WorkOrderEvent> legalEventsFrom(WorkOrderState state) {
        return TABLE.keySet().stream()
                .filter(k -> k.fromState() == state)
                .map(TransitionKey::event)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Map.Entry<TransitionKey, TransitionDescriptor> row(
            WorkOrderState from,
            WorkOrderEvent event,
            WorkOrderState to,
            Set<String> roles,
            String... guardIds) {
        return Map.entry(
                new TransitionKey(from, event),
                new TransitionDescriptor(to, roles, Arrays.asList(guardIds)));
    }

    private static Set<String> roles(String... roleValues) {
        return Set.of(roleValues);
    }

    /**
     * Returns the set of all guard identifiers referenced anywhere in the table.
     * Used by the startup validator in {@link WorkOrderTransitionServiceImpl}.
     */
    public static Set<String> allReferencedGuardIds() {
        return TABLE.values().stream()
                .flatMap(d -> d.guardIds().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
