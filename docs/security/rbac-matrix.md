# RBAC Method-Security Role Matrix

**Status:** Ratified  
**Reviewer:** _[pending sign-off]_  
**Review Date:** _[pending]_  
**Phase-1 Gate:** This document is the evidence artefact for the Phase 1 security gate.

---

## Scope

This matrix covers every publicly reachable service-layer method in the Field Service API.
Authorization annotations live on the service layer — not solely on controllers — so that
alternate caller paths (event consumers, scheduled jobs) cannot bypass checks.

The five ratified roles are drawn from `com.fieldservice.identity.domain.AppRole`:
`ADMIN`, `DISPATCHER`, `MANAGER`, `TECHNICIAN`, `CUSTOMER`.

`✅ PERMIT` — the role is granted access to invoke the operation.  
`❌ DENY` — the role is denied; the API returns HTTP 403 with a generic body.

---

## Service-Layer Method Matrix

| Operation | Service Class | Method | ADMIN | DISPATCHER | MANAGER | TECHNICIAN | CUSTOMER | Rationale |
|-----------|--------------|--------|-------|------------|---------|------------|----------|-----------|
| apply_work_order_transition | `WorkOrderTransitionApplicationService` | `apply` | ✅ | ✅ | ✅ | ✅ | ❌ | Customers have no lifecycle authority over work orders; only internal staff may drive state changes. |
| get_work_order_revisions | `WorkOrderRevisionService` | `getRevisions` | ✅ | ❌ | ✅ | ❌ | ❌ | Audit trails are restricted to managerial and administrative roles for non-disclosure; field technicians and customers must not access the full change history. |
| get_user_preference | `UserPreferencesService` | `getPreference` | ✅ | ✅ | ✅ | ✅ | ✅ | Personal appearance settings; all authenticated users may read their own preference. |
| update_user_preference | `UserPreferencesService` | `updatePreference` | ✅ | ✅ | ✅ | ✅ | ✅ | Personal appearance settings; all authenticated users may update their own preference. |

---

## HTTP Endpoint Reference (Controller-Layer)

These endpoints are gated by `@PreAuthorize` at the controller layer. They are documented
here for completeness; the service-layer annotations above provide defence-in-depth against
alternate caller paths.

| Endpoint | ADMIN | DISPATCHER | MANAGER | TECHNICIAN | CUSTOMER | Notes |
|----------|-------|------------|---------|------------|----------|-------|
| `GET /api/v1/work-orders` | ✅ | ✅ | ✅ | ✅ | ✅ | Row-scope predicate further limits visible rows per role. |
| `GET /api/v1/work-orders/{id}` | ✅ | ✅ | ✅ | ✅ | ✅ | Scope denial → 403 (TECHNICIAN cross-role) or 404 (CUSTOMER cross-account). |
| `POST /api/v1/work-orders/{id}/transitions` | ✅ | ✅ | ✅ | ✅ | ❌ | Per-event role restriction is enforced inside the transition table. |
| `GET /api/v1/work-orders/{id}/revisions` | ✅ | ❌ | ✅ | ❌ | ❌ | Full audit trail restricted to ADMIN and MANAGER. |
| `GET /api/v1/inventory/parts` | ✅ | ✅ | ✅ | ✅ | ❌ | Internal stock data not exposed to customers. |
| `GET /api/v1/inventory/stock` | ✅ | ✅ | ✅ | ✅ | ❌ | Internal stock data not exposed to customers. |
| `GET /api/v1/users/me/preferences` | ✅ | ✅ | ✅ | ✅ | ✅ | Requires authentication (`isAuthenticated()`). |
| `PUT /api/v1/users/me/preferences` | ✅ | ✅ | ✅ | ✅ | ✅ | Requires authentication (`isAuthenticated()`). |

---

## Deny-by-Default Policy

- An unrecognised role not listed in the `AppRole` enum is rejected at the authority-mapping
  stage (`RolesClaimAuthorityConverter`) and results in a denial for every protected operation.
- A missing `@PreAuthorize` annotation on a new service method causes the build to fail
  via the ArchUnit fitness rule in `MethodSecurityTest`.
- An unevaluable SpEL expression in `@PreAuthorize` is treated as a denial by Spring Security
  and logs an `expression_evaluation_error` event with the trace-id.

---

## Multi-Role Principals

A principal holding multiple roles (e.g., a user with both `ADMIN` and `DISPATCHER`) is
**permitted** if **any** granted role satisfies the `hasAnyRole(...)` expression.
This is the standard Spring Security `hasAnyRole` semantics and is explicitly tested in
`RbacMatrixTest`.

---

## Audit and Alerting

Every 403 denial emits:
- A structured log entry with `traceId`, `actorId`, `role`, `resource`, `operation`, `outcome`
- An increment of the `security.access.denied` Micrometer counter tagged with `type=ROLE_DENIED`
- SIEM alerting rules are defined in `app/src/main/resources/siem-alerting-rules.yml`

---

## Machine-Readable Form

The machine-readable version of this matrix is maintained at:
`app/src/test/resources/security/rbac-matrix.yml`

That file drives the parameterized `RbacMatrixTest` integration test. Any drift between
this document and the YAML will be caught by the completeness assertion in that test.

---

## References

- `app/src/main/java/com/fieldservice/identity/domain/AppRole.java` — role enum
- `app/src/main/java/com/fieldservice/app/config/SecurityConfiguration.java` — `@EnableMethodSecurity`
- `app/src/test/java/com/fieldservice/app/arch/MethodSecurityTest.java` — ArchUnit gate
- `.semgrep/authorization-rules.yml` — Semgrep gate
- `docs/security/scope-denial-policy.md` — 403 vs 404 scope denial decision
