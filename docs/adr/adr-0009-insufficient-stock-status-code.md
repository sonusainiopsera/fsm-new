# ADR-0009: HTTP Status Code for Insufficient Stock (422 over 409)

**Status:** Accepted  
**Date:** 2026-08-11  
**Deciders:** Platform Engineering  

## Context

The architecture document specifies 422 for business-guard refusals. The US-007
technician workspace user story specified 409 for insufficient-stock rejections.
These two sources conflict, and a consistent platform-wide contract requires an
explicit resolution.

The primary options are:

| Option | Code | Semantics |
|--------|------|-----------|
| A | 422 Unprocessable Entity | The request was well-formed but violates a business rule. The client must change the payload (reduce quantity or wait for restocking). |
| B | 409 Conflict | The request conflicts with the current state of the resource. The client may retry without changing the payload. |

## Decision

**Use 422 Unprocessable Entity for insufficient-stock refusals.**

409 is reserved for:
- Illegal lifecycle transition (work order state machine)
- Optimistic-lock conflict (concurrent modification, client retries with latest version)
- Idempotency-key conflict (same key, different payload)

422 is the correct code for any refusal where the fix requires the client to
change what it is asking for, not merely when to ask. An insufficient-stock
refusal requires either reducing the requested quantity or waiting for stock to
be replenished — neither of which is a plain "retry the same request later"
scenario.

The US-007 specification was written before the platform-wide contract was
finalised. The 409 reference in that document is superseded by this ADR.

## Consequences

- `InsufficientStockException` maps to HTTP 422 with error code `INSUFFICIENT_STOCK`.
- Per-line field errors carry `requested` and `available` quantities for the
  offending lines so the client can surface actionable remediation.
- The OpenAPI description for `POST /api/v1/work-orders/{id}/parts` cites this ADR.
- US-007 documentation and any client code that branched on 409 for stock refusals
  must be updated to branch on 422 / `INSUFFICIENT_STOCK`.
