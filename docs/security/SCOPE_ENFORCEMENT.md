# Row-Scope Enforcement (WO-113)

## Overview

Every repository query over a scoped entity applies a mandatory SQL WHERE predicate derived
from the authenticated principal's `AccessScope`. An out-of-scope row is never materialised —
it cannot leak through an error message, result count, sort artefact, or log line.

## Scoped Entities

| Entity | Scope Rule |
|--------|-----------|
| `WorkOrder` | TECHNICIAN → `assigned_technician_id = :technicianId`; CUSTOMER → `customer_id IN (:accountIds)` |
| `Site` | CUSTOMER → `customer_account_id IN (:accountIds)` |
| `Asset` | CUSTOMER → site join to `customer_account_id IN (:accountIds)` |
| `Assignment` | TECHNICIAN → `technician_id = :technicianId` |
| `Customer` | CUSTOMER → `id IN (:accountIds)` |
| `Technician` | TECHNICIAN → `id = :technicianId` |
| `StockBalance` / `StockLedger` | TECHNICIAN → filtered by technician's stock location |

Privileged roles (DISPATCHER, ADMIN, MANAGER) receive a permit-all predicate (`1=1`).

## 403 vs 404 Rule (Ratified in WO-113)

This is the **single, authoritative rule** for denying scoped access at the API boundary.
All modules must apply it through `ScopeDenialTranslator` — never per-endpoint logic.

| Scenario | HTTP Status | Body Code | Reason |
|----------|-------------|-----------|--------|
| CUSTOMER requests resource outside their account | `404 Not Found` | `NOT_FOUND` | Indistinguishable from non-existent; prevents cross-account enumeration |
| Any other role requests out-of-scope resource | `403 Forbidden` | `FORBIDDEN` | Cross-role denial with no existence disclosure |
| Unknown/unresolvable role | `403 Forbidden` | `FORBIDDEN` | Deny by default |

### Implementation

```
ScopedAccessDeniedException
    → GlobalExceptionHandler.handleScopedAccessDenied()
        → ScopeDenialTranslator.translate()
            → checks SecurityContext for CUSTOMER role
            → returns 404 (customer) or 403 (all others)
            → emits structured audit log
            → increments auth.scope_denial counter
```

**ScopeDenialTranslator** is the single translation point. Adding a new endpoint does not
require any denial-logic changes — the translator handles all cases uniformly.

## Audit and Alerting

Every scope denial emits a structured `WARN` log line:

```
scope_denial: actorId=<uuid>, role=<ROLE>, resourceType=<Entity>, resourceIdHash=<sha256prefix>,
              denialType=<cross_account|cross_role>, outcome=denied, path=<uri>, traceId=<id>
```

A Micrometer counter `auth.scope_denial` is incremented with tags:
- `denial_type`: `cross_account` (CUSTOMER cross-account) or `cross_role` (all others)
- `role`: the principal's bare role name

**SIEM alerting rule**: Alert when `auth.scope_denial{denial_type="cross_account"}` exceeds
**100 per minute per customer account** — this pattern indicates credential misuse or account
enumeration. Alert on `auth.scope_denial{denial_type="cross_role"}` exceeding **50 per minute**
per role for anomalous insider activity.

## Structural Enforcement

The ArchUnit rule in `ScopedRepositoryArchTest` enforces at build time that every domain
repository for a `ScopedEntity` type extends `ScopedRepository`. Adding a new scoped entity
without a corresponding `ScopedEntityPredicateProvider` causes application startup to fail
(via `AccessScopePredicateFactory.validateCompleteness()`).

## Security Constraints

- Row scope is applied as a SQL predicate, never as a post-fetch filter.
- Authorization denies by default: unknown/empty role → denial, never unrestricted query.
- Error responses never disclose whether an out-of-scope resource exists.
- Pagination is server-capped at 50 rows per page for all scoped collection queries.
- UI controls may hide actions for usability; server-side enforcement is the sole security boundary.
