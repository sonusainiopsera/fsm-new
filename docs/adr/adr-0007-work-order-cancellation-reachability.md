# ADR-0007 – Work Order Cancellation Reachability

**Date:** 2026-08-11
**Status:** Accepted
**Links:** BR-07 (Work Order Lifecycle Governance), BR-10 (Cancellation Policy)

---

## Context

The work order lifecycle has two states from which cancellation reachability is ambiguous:

1. **EN_ROUTE** – the technician is on the way to the site but has not yet started work.
2. **ON_HOLD** – work has been paused (awaiting parts, site access, customer approval, etc.).

The question is whether a DISPATCHER or ADMIN may apply the `CANCEL` event directly from
either of these states, or whether the work order must first be moved back to an intermediate
state before cancellation is possible.

---

## Decision

Both transitions are **permitted**:

| From       | Event  | To        |
|------------|--------|-----------|
| EN_ROUTE   | CANCEL | CANCELLED |
| ON_HOLD    | CANCEL | CANCELLED |

---

## Rationale

### EN_ROUTE → CANCELLED

A technician en route has not yet begun any billable or reportable work.
Operational scenarios that require cancellation at this stage include:

- Customer or facility manager calls to cancel the appointment while the technician is in transit.
- Safety or weather advisory prevents site access.
- An emergency re-dispatch re-prioritises the technician to a higher-urgency work order.

Requiring the dispatcher to first move the work order back to ASSIGNED before cancelling adds
no protective value — it only delays the operational action. The SLA clock and billing rules
apply from IN_PROGRESS, not from EN_ROUTE, so no financial invariant is violated.

### ON_HOLD → CANCELLED

Work on hold may remain paused for an indefinite period. Business scenarios include:

- Required parts are no longer obtainable (discontinued equipment).
- Customer goes out of business or terminates the service contract.
- Regulatory or safety hold that cannot be resolved within the service window.

Forcing a RESUME → CANCEL round-trip is operationally wrong: it implies the work
was restarted only to be immediately cancelled, which distorts reporting and SLA metrics.
Administrative cancellation of paused work is a legitimate, common operation.

### Terminal semantics

Both CLOSED and CANCELLED are **terminal states**. No event defined in the table — including
those available to ADMIN — produces an outbound transition from a terminal state.
This invariant is enforced by the absence of entries in the transition table and verified
by a dedicated unit test.

---

## Consequences

- The `WorkOrderTransitionTable` includes both EN_ROUTE → CANCELLED and ON_HOLD → CANCELLED
  entries, each restricted to DISPATCHER and ADMIN roles.
- The parameterised 8×8 matrix test asserts these cells as legal and verifies no additional
  cancellation reachability exists (e.g. COMPLETED → CANCELLED is refused).
- Future stories that introduce partial-completion billing must record the cancellation reason
  and the last known state so revenue-recognition rules can be applied correctly; that
  enrichment does not change the reachability decision recorded here.
