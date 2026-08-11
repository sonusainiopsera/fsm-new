# ADR-0008: HTTP Status Code for Insufficient-Stock Refusal

**Status:** Accepted  
**Date:** 2026-08-11  
**Deciders:** Platform Architecture Team  
**Story:** WO-149

## Context

The architecture specifies 422 Unprocessable Entity for any business-guard refusal where the
request is syntactically valid but semantically rejected by a business rule. US-007
(the original parts-logging user story) instead specified 409 Conflict for insufficient-stock
responses, creating a discrepancy between the two documents.

Insufficient stock is not a conflict in the HTTP sense — there is no concurrency race and
no optimistic-lock collision to report. The request is coherent, authenticated, and correctly
structured; the business rule BR-16 (stock must never go negative) simply refuses the
specific quantity requested.

## Decision

Use **HTTP 422** with code `INSUFFICIENT_STOCK` for all insufficient-stock refusals.

Reserve HTTP 409 for:
- Illegal work-order state transitions (`WORK_ORDER_ILLEGAL_TRANSITION`)
- Optimistic-lock conflicts (`WORK_ORDER_VERSION_CONFLICT`, `CONFLICT`)
- Idempotency-key conflicts (`IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS`)

## Consequences

- Clients can distinguish a business-rule refusal (422) from a concurrent-edit conflict
  (409) without inspecting the `code` field.
- The `code` field `INSUFFICIENT_STOCK` carries per-line `fieldErrors` with
  `requested` vs `available` quantities for actionable client display.
- US-007 documents a deviation: the 409 specified there is superseded by this ADR.
  The OpenAPI operation description for `POST /api/v1/work-orders/{id}/parts` cites
  this ADR number.
- This ADR aligns with the platform-wide contract established in the architecture
  document: 422 = semantic business refusal, 409 = state conflict.
