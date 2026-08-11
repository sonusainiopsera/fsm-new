package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.fieldservice.domain.workorder.WorkOrderState.*;
import static com.fieldservice.domain.workorder.lifecycle.WorkOrderEvent.*;

/**
 * Single declarative source of work-order lifecycle rules.
 *
 * <p>The table is the ONLY place in the codebase where lifecycle transitions are defined.
 * No controller, service, or domain method may branch on {@link WorkOrderState} to determine
 * whether a move is legal — that question is always answered here. See ADR-0007 for the
 * decision on cancellation reachability from EN_ROUTE and ON_HOLD.
 *
 * <p>The map is built once at class initialisation and is unmodifiable; any attempt to
 * call {@code put()} or {@code remove()} will throw {@link UnsupportedOperationException}.
 *
 * <p>Package-private: callers outside this package interact via {@link WorkOrderTransitionService}.
 */
final class WorkOrderTransitionTable implements WorkOrderTransitionPort {

    // Role authority strings matching Spring Security granted-authority names
    static final String ROLE_DISPATCHER  = "DISPATCHER";
    static final String ROLE_TECHNICIAN  = "TECHNICIAN";
    static final String ROLE_ADMIN       = "ADMIN";
    static final String ROLE_MANAGER     = "MANAGER";

    private static final Map<TransitionKey, TransitionDescriptor> TABLE;

    static {
        TABLE = Collections.unmodifiableMap(Map.ofEntries(

            // ── NEW ──────────────────────────────────────────────────────────────
            // A dispatcher assigns a technician; the job enters the assigned queue.
            entry(NEW, ASSIGN,
                  ASSIGNED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // A dispatcher or admin cancels a brand-new job before dispatch.
            entry(NEW, CANCEL,
                  CANCELLED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ── ASSIGNED ─────────────────────────────────────────────────────────
            // Technician departs for site; tracked as en-route.
            entry(ASSIGNED, DEPART,
                  EN_ROUTE,
                  Set.of(ROLE_TECHNICIAN, ROLE_ADMIN),
                  List.of()),

            // Job cancelled after assignment but before departure.
            entry(ASSIGNED, CANCEL,
                  CANCELLED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ── EN_ROUTE ──────────────────────────────────────────────────────────
            // Technician arrives and starts work.
            entry(EN_ROUTE, START,
                  IN_PROGRESS,
                  Set.of(ROLE_TECHNICIAN, ROLE_ADMIN),
                  List.of()),

            // Unexpected hold while en-route (e.g. access not yet available).
            entry(EN_ROUTE, HOLD,
                  ON_HOLD,
                  Set.of(ROLE_TECHNICIAN, ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ADR-0007: EN_ROUTE → CANCELLED is PERMITTED (see docs/adr/adr-0007).
            entry(EN_ROUTE, CANCEL,
                  CANCELLED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ── IN_PROGRESS ───────────────────────────────────────────────────────
            // Active job placed on hold (parts unavailable, safety stop, etc.).
            entry(IN_PROGRESS, HOLD,
                  ON_HOLD,
                  Set.of(ROLE_TECHNICIAN, ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // Technician completes all field work; job awaits admin closure.
            entry(IN_PROGRESS, COMPLETE,
                  COMPLETED,
                  Set.of(ROLE_TECHNICIAN, ROLE_ADMIN),
                  List.of("LABOUR_TIME_RECORDED")),

            // Job cancelled while in progress (customer request or safety escalation).
            entry(IN_PROGRESS, CANCEL,
                  CANCELLED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ── ON_HOLD ───────────────────────────────────────────────────────────
            // Held job resumes — always re-enters IN_PROGRESS, never en-route.
            entry(ON_HOLD, RESUME,
                  IN_PROGRESS,
                  Set.of(ROLE_TECHNICIAN, ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ADR-0007: ON_HOLD → CANCELLED is PERMITTED (see docs/adr/adr-0007).
            entry(ON_HOLD, CANCEL,
                  CANCELLED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN),
                  List.of()),

            // ── COMPLETED ─────────────────────────────────────────────────────────
            // Dispatcher or manager closes a completed job; no further moves possible.
            entry(COMPLETED, CLOSE,
                  CLOSED,
                  Set.of(ROLE_DISPATCHER, ROLE_ADMIN, ROLE_MANAGER),
                  List.of())

            // CLOSED and CANCELLED have no outbound entries — they are terminal states.
        ));
    }

    @Override
    public Optional<TransitionDescriptor> resolve(WorkOrderState fromState, WorkOrderEvent event) {
        return Optional.ofNullable(TABLE.get(new TransitionKey(fromState, event)));
    }

    @Override
    public Set<WorkOrderEvent> legalEventsFrom(WorkOrderState fromState) {
        return TABLE.keySet().stream()
                .filter(k -> k.fromState() == fromState)
                .map(TransitionKey::event)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static Map.Entry<TransitionKey, TransitionDescriptor> entry(
            WorkOrderState from,
            WorkOrderEvent event,
            WorkOrderState to,
            Set<String> roles,
            List<String> guards) {
        return Map.entry(
                new TransitionKey(from, event),
                new TransitionDescriptor(to, roles, guards));
    }
}
