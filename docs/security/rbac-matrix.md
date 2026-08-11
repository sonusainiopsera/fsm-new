# RBAC Method-Security Matrix (WO-114)

**Status:** Ratified  
**Date:** 2026-08-11  
**Reviewer:** security-team  
**Phase 1 Security Gate Evidence Artefact**

## Overview

This document is the authoritative RBAC matrix for the Field Service platform. Every service
operation is listed with its allowed roles and a rationale. The matrix is machine-readable at
`app/src/test/resources/security/rbac-matrix.yml` and drives the `RbacMatrixTest` parameterized
integration test; changes to either document must be synchronised.

Method-security annotations live on the service layer so a second caller path (event consumer,
scheduled job, direct service call) cannot bypass the check. The `@EnableMethodSecurity`
configuration is in `SecurityFilterChainConfig`.

## Role Definitions

| Role | Description | Scope |
|------|-------------|-------|
| `ADMIN` | System administrator | All data, all operations |
| `DISPATCHER` | Field operations dispatcher | All work orders, all lifecycle events |
| `MANAGER` | Operations manager (read-only) | All work orders read-only; no mutations |
| `TECHNICIAN` | Field technician | Own assigned work orders; own lifecycle events |
| `CUSTOMER` | Customer portal user | Own account's work orders (read-only) |

## Authorization Matrix

Legend: ✓ = allow · ✗ = deny

| Operation | ADMIN | DISPATCHER | MANAGER | TECHNICIAN | CUSTOMER | Annotation |
|-----------|:-----:|:----------:|:-------:|:----------:|:--------:|------------|
| **Work Order Lifecycle** |
| `WorkOrderTransitionService.applyTransition()` | ✓ | ✓ | ✗ | ✓ | ✗ | `@PreAuthorize("hasAnyRole('DISPATCHER','TECHNICIAN','ADMIN')")` |
| `WorkOrderTransitionService.applyEvent()` | ✓ | ✓ | ✗ | ✓ | ✗ | `@PreAuthorize("hasAnyRole('DISPATCHER','TECHNICIAN','ADMIN')")` |
| **Audit / Revisions** |
| `RevisionQueryService.getWorkOrderRevisions()` | ✓ | ✓ | ✓ | ✗ | ✗ | `@PreAuthorize("hasAnyRole('DISPATCHER','MANAGER','ADMIN')")` |
| `RevisionQueryService.countWorkOrderRevisions()` | ✓ | ✓ | ✓ | ✗ | ✗ | `@PreAuthorize("hasAnyRole('DISPATCHER','MANAGER','ADMIN')")` |
| **Work Order Read** |
| `WorkOrderController.getWorkOrder()` | ✓ | ✓ | ✓ | ✓ | ✓ | `isAuthenticated()` (row-scope enforced by ScopedQueryExecutor) |
| **Inventory** |
| `StockQueryServiceImpl.listParts()` | ✓ | ✓ | ✓ | ✓ | ✗ | `@PreAuthorize("hasAnyRole('DISPATCHER','TECHNICIAN','ADMIN','MANAGER')")` |
| `StockQueryServiceImpl.listStock()` | ✓ | ✓ | ✓ | ✓ | ✗ | `@PreAuthorize("hasAnyRole('DISPATCHER','TECHNICIAN','ADMIN','MANAGER')")` |
| **User Preferences** |
| `UserPreferencesService.getPreferences()` | ✓ | ✓ | ✓ | ✓ | ✓ | `@PreAuthorize("isAuthenticated()")` |
| `UserPreferencesService.updatePreferences()` | ✓ | ✓ | ✓ | ✓ | ✓ | `@PreAuthorize("isAuthenticated()")` |

## Deny-by-Default Rules

- An absent or unevaluable authorization expression → denial (Spring Security default)
- An unrecognised role → no authority matched → denial
- A newly added service method with no annotation → ArchUnit build failure
- Background job invocations must run under an authenticated security context

## Security Constraints

- 403 responses never disclose whether a target resource exists
- Annotations must be on the service layer, not only on controllers
- This matrix and the code must not drift; `RbacMatrixTest` enforces the invariant at build time

## Cross-reference

- Row-scope enforcement: `docs/security/SCOPE_ENFORCEMENT.md`
- Implementation config: `SecurityFilterChainConfig.java` (`@EnableMethodSecurity`)
- ArchUnit enforcement: `MethodSecurityTest.java`
- Semgrep enforcement: `.semgrep/authorization-rules.yml`
