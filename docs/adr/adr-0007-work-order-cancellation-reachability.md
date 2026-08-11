# ADR-0007: Work Order Cancellation Reachability from EN_ROUTE and ON_HOLD

**Date:** 2026-08-11  
**Status:** Accepted  
**Deciders:** Engineering, Operations, Dispatch team leads  
**Business Rules:** BR-07 (job cannot be silently abandoned), BR-10 (customer must be notified of any cancellation)

---

## Context

The confirmed state vocabulary for work orders is:
`NEW → ASSIGNED → EN_ROUTE → IN_PROGRESS → ON_HOLD → COMPLETED → CLOSED`
with `CANCELLED` as a separate terminal state reachable from active states.

The cancellation ambiguity surfaces two open questions:

1. **EN_ROUTE → CANCELLED**: A technician has departed for the site but the job must be cancelled (e.g., the customer withdrew the request while the technician was in transit, or a safety hazard was identified at the site).

2. **ON_HOLD → CANCELLED**: A job was placed on hold (e.g., parts unavailable, site access denied) and later determined to be unresolvable — the job must be cancelled without first resuming to IN_PROGRESS.

The alternative design — requiring the transition chain `EN_ROUTE → IN_PROGRESS → CANCEL` or `ON_HOLD → RESUME → CANCEL` — was considered and rejected.

---

## Decision

**Both EN_ROUTE → CANCELLED and ON_HOLD → CANCELLED are PERMITTED.**

The `CANCEL` event is legal from: `NEW`, `ASSIGNED`, `EN_ROUTE`, `IN_PROGRESS`, `ON_HOLD`.  
`COMPLETED`, `CLOSED`, and `CANCELLED` have no outbound `CANCEL` transition; the table contains no such entry.

---

## Rationale

### Why EN_ROUTE → CANCELLED must be permitted

Requiring a technician to "START" work (EN_ROUTE → IN_PROGRESS) before cancellation creates a misleading audit record: the Envers revision history would show the technician starting work on a job that was in fact never started. This contradicts BR-07 (job cannot be silently abandoned) by obscuring the true sequence of events.

Operationally, cancellation while en-route is a real and regular occurrence (customer-side cancellation, safety escalation, dispatch error). Preventing it at the state-machine level forces dispatchers to workaround by manually editing state through back-channels, which is exactly the audit-integrity failure the transition table is designed to prevent.

### Why ON_HOLD → CANCELLED must be permitted

A job placed on hold often remains on hold until a determination is made: resume or cancel. Requiring `ON_HOLD → RESUME → CANCEL` again produces a misleading audit trail (a spurious IN_PROGRESS revision) and creates an artificial operational barrier. The correct representation of "held job determined to be unresolvable" is a single CANCEL event from ON_HOLD.

### Why COMPLETED → CANCELLED is NOT permitted

A completed job has field work verified by the technician. Allowing cancellation post-completion would destroy the labour record and parts-consumption audit trail. `CLOSE` is the only exit from `COMPLETED`; the `CANCEL` event has no entry for `(COMPLETED, CANCEL)` in the table.

### Why ADMIN is not an escape hatch from terminal states

`CLOSED` and `CANCELLED` have zero outbound transitions for any role, including `ADMIN`. A closed or cancelled job is immutable. Administrative correction, if ever needed, is an explicit compensating action (a new work order) recorded as a distinct audit event, not a state rollback.

---

## Consequences

- The transition table contains entries for `(EN_ROUTE, CANCEL) → CANCELLED` and `(ON_HOLD, CANCEL) → CANCELLED`, both gated to `DISPATCHER` and `ADMIN` roles.
- The parameterized 8×8 matrix test (`TransitionMatrixTest`) must assert these two cells as legal moves.
- `WorkOrderEvent.CANCEL` therefore has five legal source states: `NEW`, `ASSIGNED`, `EN_ROUTE`, `IN_PROGRESS`, `ON_HOLD`.
- Cancellation from `EN_ROUTE` or `ON_HOLD` does NOT produce an intervening `IN_PROGRESS` revision — the Envers audit trail accurately reflects the operational reality.
- Downstream notification logic (BR-10) must trigger the customer-notification path on all five CANCEL source states, not only on `IN_PROGRESS → CANCEL`.

---

## Alternatives Considered

| Alternative | Reason rejected |
|---|---|
| Require EN_ROUTE → IN_PROGRESS → CANCEL | Creates spurious audit revision; forces artificial state transition |
| Require ON_HOLD → RESUME → CANCEL | Same spurious revision problem; creates artificial operational barrier |
| Allow COMPLETED → CANCELLED (ADMIN only) | Destroys labour and parts audit trail; compensating action is the correct model |
| Allow CLOSED → CANCELLED | No operational scenario requires this; terminal immutability is a stronger guarantee |
