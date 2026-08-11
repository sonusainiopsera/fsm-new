# Forge Implementation Log

| Field | Value |
|-------|-------|
| Project | 4f69ba9f-4b3a-4f0d-bab3-d0343cbcdd50 |
| Branch | forge/ai-powered-field-service-manag-64043c14-run7-109wo |
| Started | 2026-08-11T10:49:12Z |

---

## WO-009: User Story: WO-009 - Mandatory AccessScope row-level query predicate enforcement
- **Status:** completed
- **Commit:** `9946f8c`
- **Files:** 46 (+3362/-1)
- **Duration:** 1469ss
- **Approach:** Created a Maven multi-module Spring Boot 3 / Java 21 project with two modules: 'platform' (shared security primitives) and 'app' (application entry point + domain). The platform module provides an immutable AccessScope record, AccessScopeResolver (extracts userId/roles/technicianId/customerAccountIds from JWT once per request), EntityScopeSpec<T> strategy interface, AccessScopePredicateFactory (registry with startup validation), ScopedEntity marker interface, ScopedRepository (extends JpaRepository + JpaSpecificationExecutor), and ScopedQueryExecutor (composes scope predicate into every read including count queries). The app module wires SecurityConfiguration (@EnableMethodSecurity, OAuth2 resource server with JwtAuthenticationConverter mapping roles claim), GlobalExceptionHandler (uniform 403 non-disclosure for ScopedAccessDeniedException), WorkOrderController with @PreAuthorize and ScopedQueryExecutor integration, and per-entity EntityScopeSpec beans for WorkOrder/Site/CustomerAccount. Row-scope predicates: DISPATCHER/ADMIN/MANAGER=conjunction (permit-all), TECHNICIAN=cb.equal(assignedTechnicianId, scope.technicianId()), CUSTOMER=site INNER JOIN + customerAccountId IN scope.customerAccountIds(). Both data and count queries use the same composed specification so totalElements is always scoped.

## WO-002: User Story: WO-002 - Flyway baseline schema with UUIDv7 keys and integrity constraints
- **Status:** completed
- **Commit:** `9a37ad4`
- **Files:** 28 (+1383/-136)
- **Duration:** 1195ss
- **Approach:** Replaced the minimal V1__init.sql with a comprehensive 4-migration Flyway set (V1__baseline_core.sql: all 15 tables; V2__sla_policy.sql: SLA table + seeded rows; V3__indexes.sql: all P0 indexes; V4__seed_reference_data.sql: representative test data). Added RFC-9562-compliant UuidV7 generator in the platform module using 48-bit unix millis prefix, 12-bit monotonic sub-millisecond sequence, and 62 bits random. Renamed the customer_account table to customer and updated site.customer_account_id to customer_id, updated WorkOrderStatus enum (OPEN→NEW, added CLOSED), renamed work_order.status column to state, and added priority column. Updated all JPA entity field/column mappings, ScopeSpec predicates, and test fixtures to align with the new schema. All version columns changed from BIGINT/Long to INTEGER/Integer per spec. New minimal JPA entity stubs created for all 10 new tables to support Hibernate startup validation in production profile.

## WO-006: User Story: WO-006 - Uniform error contract and strict request validation pipeline
- **Status:** completed
- **Commit:** `01aec5b`
- **Files:** 3 (+15/-6)
- **Duration:** 763ss
- **Approach:** Platform module receives the full uniform error contract: ErrorCode enum, FieldError and ApiErrorResponse records, 7 typed domain exception classes, @ValidEnum and @SafeText custom validation annotations, and a comprehensive GlobalExceptionHandler @RestControllerAdvice covering every agreed status code (400/401/403/404/409/422/429+Retry-After/503/500). All 403 outcomes return body-identical responses for non-disclosure. Jackson is configured to fail-on-unknown-properties and fail-on-null-for-primitives. An ErrorControllerFallback handles filter-stage failures. The old app.error.ErrorResponse and app.error.GlobalExceptionHandler (ACCESS_DENIED code) are deleted and replaced by the platform types. CrossRoleProbeMatrixTest updated from ACCESS_DENIED to FORBIDDEN.
