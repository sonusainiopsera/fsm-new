# Forge Implementation Log

| Field | Value |
|-------|-------|
| Project | 09957548-cd85-40f1-81ef-b1f2e8eefdda |
| Branch | forge/ai-powered-field-service-manag-64043c14-run6-109wo |
| Started | 2026-08-11T05:32:05Z |

---

## WO-009: User Story: WO-009 - Mandatory AccessScope row-level query predicate enforcement
- **Status:** completed
- **Commit:** `2c46c0c`
- **Files:** 51 (+4089/-1)
- **Duration:** 1197ss
- **Approach:** Set up a Maven multi-module project (platform + app) for Java 21 / Spring Boot 3.5, then implemented the full mandatory AccessScope row-level query predicate enforcement layer. The platform module provides: AccessScope record (userId, roles, technicianId, customerAccountIds), request-scoped AccessScopeResolver building the scope from JWT claims once per request, AccessScopePredicateFactory aggregating per-entity ScopedEntityPredicateProvider beans with startup validation that fails fast if any scoped entity type lacks a registered provider, ScopedRepository base interface, and ScopedQueryExecutor composing scope predicates into every read (data query + COUNT query via JPA Specification). The app module adds SecurityConfiguration (OAuth2 Resource Server, JwtAuthenticationConverter mapping roles claim to ROLE_-prefixed authorities, @EnableMethodSecurity), five domain entities implementing ScopedEntity with matching predicate providers for all roles (permit-all for DISPATCHER/ADMIN/MANAGER, assigned_technician_id predicate for TECHNICIAN, site.customer_account_id IN query for CUSTOMER), GlobalExceptionHandler for uniform 403 non-disclosure envelope, Flyway migration V1, and comprehensive tests.

## WO-002: User Story: WO-002 - Flyway baseline schema with UUIDv7 keys and integrity constraints
- **Status:** completed
- **Commit:** `0dd75cd`
- **Files:** 43 (+2367/-506)
- **Duration:** 1295ss
- **Approach:** Replaced the V1 placeholder schema with a full 15-table production-grade DDL covering all core entities, added V2 (sla_policy + seed rows), V3 (index set for P0 read paths), and V4 (role seed data). Implemented RFC 9562 UUIDv7 generation in the platform module with a Hibernate BeforeExecutionGenerator and a @GeneratedUuidV7 meta-annotation; wired into a @MappedSuperclass BaseEntity that all main entities extend. Updated all existing entities (Site, WorkOrder, Asset, Assignment) to extend BaseEntity and aligned their FK columns with the new schema. Created all new entities (Customer, AppUser, Technician, TechnicianCertification, Part, StockLocation, StockBalance, StockLedger, SlaPolicy) with proper JPA mappings matching the V1/V2 schema exactly. Updated row-scope predicates: WorkOrder now scopes CUSTOMER by the direct customer_id FK; Site and Asset predicates updated to use customerId. Created new scope predicate providers for Customer, Technician, StockBalance, StockLedger. Replaced the obsolete StockMovement entity (table removed in V1) with @Deprecated stubs. Updated V100 test fixtures to insert app_user, customer, technician rows required by new FK constraints. Fixed the WorkOrderScopePredicateProviderTest unit test to reflect the new direct customerId scope predicate.

## WO-006: User Story: WO-006 - Uniform error contract and strict request validation pipeline
- **Status:** completed
- **Commit:** `2fb2cd0`
- **Files:** 24 (+1107/-71)
- **Duration:** 469ss
- **Approach:** Implemented a typed domain exception hierarchy in the platform module (7 exception classes covering all required HTTP status codes), updated ErrorEnvelope with a fieldErrors list and new Code constants matching the WO spec, added SafeText and AllowedValues validation annotations with character allow-list and enum allow-list validators respectively, rewrote GlobalExceptionHandler with a comprehensive handler matrix for all exception types plus X-Trace-Id response header, updated SecurityConfiguration to use new FORBIDDEN code and add X-Trace-Id header, added server.error.include-stacktrace=never and include-message=never to application.yml, created ErrorControllerFallback for filter-stage errors, and created a MockMvc test matrix (ErrorContractTest) with a test-only endpoint controller (TestErrorController) plus invalid-payload fixture files.

## WO-182: User Story: WO-182 - Design token contract with light and dark value sets
- **Status:** completed
- **Commit:** `1d47d52`
- **Files:** 23 (+1848/-0)
- **Duration:** 864ss
- **Approach:** Created the field-service-web frontend module from scratch. Implemented a 77-token design contract expressed as three CSS files: tokens.contract.css (vocabulary only), tokens.light.css (:root bindings), and tokens.dark.css ([data-appearance='dark'] overrides). The contract covers typography (7-step scale, 1.5 line-height, tightened tracking ≥20px, tabular-nums), spacing (4px base rhythm), radius (6/10/14/9999px), elevation (exactly 2 levels + hairline border), motion (120/180/240ms + single easing), and colour (neutral ramp, one accent Q13, four semantic families). A Node.js generate script parses the contract CSS and emits a machine-readable JSON mirror. Stylelint enforces zero hard-coded values in application CSS; a custom ESLint rule enforces the same in JSX style props. tsconfig.json provides checkJs+strict as the Q6 compensating control. Vitest test suite validates contract completeness, bijection parity, motion ceiling, semantic family count, numeric token, lint gate behaviour (programmatic Stylelint and ESLint), and appearance change.

## WO-003: User Story: WO-003 - Hibernate Envers revision history with actor-aware REVINFO
- **Status:** completed
- **Commit:** `06c7d41`
- **Files:** 18 (+1090/-0)
- **Duration:** 1438ss
- **Approach:** Enabled Hibernate Envers on the six business-critical entities (work_order, assignment, technician_certification, app_user, site, sla_policy). Created a custom AuditRevisionEntity + AuditRevisionListener in the platform module that reads actor identity from Spring SecurityContextHolder and traceId/clientIp from MDC; falls back to a documented synthetic 'system' actor when no principal is present. All six audit tables plus REVINFO and the revision sequence are created by Flyway V5 (never by Hibernate; ddl-auto=validate enforced). V6 migration creates the fieldservice role if absent and applies SELECT+INSERT-only grants, explicitly revoking UPDATE+DELETE. A read-only paginated revision query service and REST endpoint (GET /api/v1/work-orders/{id}/revisions) expose revision history restricted to DISPATCHER/MANAGER/ADMIN. For entities with dual FK mappings (UUID field + @ManyToOne), the UUID field is audited and the @ManyToOne is marked @NotAudited to avoid duplicate columns in audit tables. AppUser.passwordHash is excluded via @NotAudited.

## WO-007: User Story: WO-007 - Paginated response envelope with capped size and keyset fallback
- **Status:** completed
- **Commit:** `40ddd03`
- **Files:** 20 (+1608/-3)
- **Duration:** 1359ss
- **Approach:** Implemented platform pagination primitives as Java records in the platform module (PagedResponse, PageMeta, PageLinks, PageQuery, SortAllowList, SortField, KeysetCursor, InvalidSortException, InvalidCursorException). PageQuery enforces MAX_SIZE=50 via compact constructor clamping. SortAllowList maps public client names to JPA property names and rejects unknowns — the injection defence for sorting. KeysetCursor encodes base64url(JSON{fp,ca,id}).base64url(HMAC-SHA256) for tamper-evident cursor pagination with fingerprint-based cross-sort-order replay detection. SpecificationPageService in the app module delegates to ScopedQueryExecutor ensuring scope predicates are always applied; offset mode returns exact scoped totals, keyset mode (page>20 threshold) returns estimated=-1 metadata and a cursor in links.next; a Micrometer timer is tagged by resource and mode. GlobalExceptionHandler handles InvalidSortException and InvalidCursorException as 400 with fieldErrors. AbstractIntegrationTest gains @AutoConfigureMockMvc for controller-layer tests.

## WO-008: User Story: WO-008 - Idempotency-Key handling for all mutating endpoints
- **Status:** completed
- **Commit:** `adcaa0d`
- **Files:** 14 (+1561/-0)
- **Duration:** 1017ss
- **Approach:** Implemented end-to-end idempotency-key protocol using a OncePerRequestFilter (api profile) that validates key format, computes SHA-256 request digest (raw body never stored), claims the key via REQUIRES_NEW transaction, and dispatches a sealed-interface ClaimResult. On Claimed: executes request, captures 2xx responses for replay, releases on 4xx/5xx. On Replay: returns stored response with Idempotency-Replay header. On Conflict/InProgress: returns structured 409 via uniform error envelope. Purge job uses pg_try_advisory_lock for leader election, bounded batch deletes. Keys scoped by (idempotency_key, user_id, endpoint) unique constraint.

## WO-108: User Story: WO-108 - Model users, roles, credentials, and token families
- **Status:** completed
- **Commit:** `3920cf0`
- **Files:** 18 (+1341/-5)
- **Duration:** 911ss
- **Approach:** DDL-first approach: authored V8-V12 Flyway migrations, then derived JPA entities. V8 expands app_user with nullable password_hash (widened to VARCHAR(256)), external_subject reserved column, and case-insensitive email unique index. V9 creates role_assignment with FK ON DELETE RESTRICT, CHECK constraint mirroring IdentityRole enum, and UNIQUE(user_id, role_name). V10 creates refresh_token_family and refresh_token storing only SHA-256 hex hashes. V11 creates role_assignment_aud wired to existing REVINFO. V12 grants SELECT/INSERT (withholds UPDATE/DELETE) on role_assignment_aud to the fieldservice runtime role. Updated existing AppUser entity in-place (not duplicated) to avoid dual-entity JPA conflict. Created identity.domain package with RoleAssignment (@Audited), RefreshTokenFamily, RefreshToken entities and package-level repositories.

## WO-123: User Story: WO-123 - Declarative work order lifecycle transition table
- **Status:** completed
- **Commit:** `e41636a`
- **Files:** 16 (+1409/-0)
- **Duration:** 1296ss
- **Approach:** Declarative table approach: encode all lifecycle rules as an immutable Map<TransitionKey,TransitionDescriptor> built once at class init using Map.ofEntries wrapped in Collections.unmodifiableMap. TransitionKey is a record(fromState, event), TransitionDescriptor is a record(toState, requiredRoles, guardIds). 13 legal transitions across 8 states and 8 events. WorkOrderTransitionServiceImpl is the sole code path that calls WorkOrder.setState — it loads the work order via EntityManager.find, resolves the descriptor, checks caller authorities from SecurityContextHolder, evaluates registered guard beans, then saves. GuardResult is a sealed interface (Satisfied/Refused) for the deferred guards story. ADR-0007 resolves cancellation reachability: EN_ROUTE→CANCELLED and ON_HOLD→CANCELLED both permitted.

## WO-176: User Story: WO-176 - Provider-agnostic AI gateway with resilience and cost guardrails
- **Status:** completed
- **Commit:** `17a0470`
- **Files:** 33 (+1780/-0)
- **Duration:** 1255ss
- **Approach:** Built the AI gateway as a two-layer module: a public api package (AiGatewayPort interface + provider-agnostic request/response records + typed exceptions) and a package-private internal package (HttpAiProviderAdapter wrapped with Resilience4j TimeLimiter/CircuitBreaker/Bulkhead/Retry, EgressAllowList SSRF protection, EnvironmentSecretsProvider, RedisUsageCapService with Redis INCR+EXPIRE, AiGatewayMetrics, FeatureFlagGuardAdapter). The config class AiGatewayResilienceConfig (profile !test) wires the full chain; the test profile registers FakeAiGatewayAdapter instead. Redis autoconfiguration is excluded from the test profile. The feature flag ai.copilot.enabled defaults to false so the platform ships with zero AI network calls.
