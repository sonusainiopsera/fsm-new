# ADR-0007: Work Order Cancellation Reachability

**Date:** 2026-08-11  
**Status:** Accepted  
**Business rules:** BR-07 (work order lifecycle governance), BR-10 (cancellation policy)

## Context

The lifecycle state machine must decide whether EN_ROUTE and ON_HOLD are permitted
to transition directly to CANCELLED, or whether callers must first reverse to an
earlier state (e.g. EN_ROUTE → ASSIGNED → CANCELLED).

Two positions were considered:

1. **Restricted cancellation** — only NEW, ASSIGNED, and IN_PROGRESS can be cancelled
   directly; EN_ROUTE and ON_HOLD must first be reversed.
2. **Open cancellation** — any non-terminal state may be cancelled directly.

## Decision

**EN_ROUTE → CANCELLED and ON_HOLD → CANCELLED are both permitted.**

All five non-terminal states (NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD) have a
direct CANCEL transition leading to CANCELLED.

## Rationale

- **Operational reality.** A dispatcher must be able to cancel a work order regardless
  of whether the technician is already travelling or has paused on-site. Requiring a
  detour through ASSIGNED would create an artificial intermediate state with no
  business meaning and would introduce a race condition window.
- **Simplicity.** Five symmetric CANCEL edges are easier to test and reason about than
  three plus two special-case reversal paths.
- **Service-level impact.** Cancellation regardless of progress state is consistent with
  the SLA requirements documented in BR-10, which defines cancellation as an
  exceptional administrative act by a privileged role (DISPATCHER, ADMIN, or MANAGER).
  Those roles are trusted to cancel at any non-terminal point.
- **Irreversibility.** CANCELLED is a terminal state with zero outbound transitions even
  for ADMIN. No escape hatch exists; see the transition table for confirmation.

## Consequences

The transition table contains exactly 13 entries:

| From        | Event    | To          |
|-------------|----------|-------------|
| NEW         | ASSIGN   | ASSIGNED    |
| NEW         | CANCEL   | CANCELLED   |
| ASSIGNED    | UNASSIGN | NEW         |
| ASSIGNED    | DEPART   | EN_ROUTE    |
| ASSIGNED    | CANCEL   | CANCELLED   |
| EN_ROUTE    | START    | IN_PROGRESS |
| EN_ROUTE    | CANCEL   | CANCELLED   |
| IN_PROGRESS | HOLD     | ON_HOLD     |
| IN_PROGRESS | COMPLETE | COMPLETED   |
| IN_PROGRESS | CANCEL   | CANCELLED   |
| ON_HOLD     | RESUME   | IN_PROGRESS |
| ON_HOLD     | CANCEL   | CANCELLED   |
| COMPLETED   | CLOSE    | CLOSED      |

CLOSED and CANCELLED have zero outbound transitions. Any attempt to apply an event
to a terminal state yields `WORK_ORDER_ILLEGAL_TRANSITION`.
