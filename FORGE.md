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

## WO-182: User Story: WO-182 - Design token contract with light and dark value sets
- **Status:** completed
- **Commit:** `861b9c6`
- **Files:** 26 (+1965/-0)
- **Duration:** 852ss
- **Approach:** Created the field-service-web repository as a new directory within the monorepo. Implemented the complete 84-token design contract as CSS custom properties in three files: tokens.contract.css (vocabulary declaration with documentation comments), tokens.light.css (:root value set), and tokens.dark.css ([data-appearance=dark] value set). All 84 tokens are in bijection across all three files and the machine-readable tokens.contract.json (verified by Python and by the parity test suite). Token coverage: typography (20 tokens: 7-size scale, 4 weights, 3 line-heights, 2 tracking, 2 font stacks + brand placeholder Q13, numeric-figures), spacing (8 tokens), radius (4 tokens), elevation (4 tokens: 2 levels + hairline border + scrim), motion (4 tokens: 3 durations ≤300ms + 1 easing), colour (48 tokens: 12-step neutral ramp, 7 surface/text tokens, 5 accent tokens (Q13), 4×5 semantic tokens). Stylelint enforces var(--token) references on 15+ property families. Custom ESLint rule rejects literal colors/px/ms in JSX style props. TypeScript JSDoc checking enabled via tsconfig checkJs+strict. Vitest test suite validates parity, motion ceiling, semantic family count, lint gates, and ESLint rule behavior.

## WO-003: User Story: WO-003 - Hibernate Envers revision history with actor-aware REVINFO
- **Status:** completed
- **Commit:** `75eaf1b`
- **Files:** 22 (+1336/-1)
- **Duration:** 1167ss
- **Approach:** Enabled Hibernate Envers on the 6 business-critical entities by adding the hibernate-envers dependency to both Maven modules and annotating entities with @Audited/@NotAudited. Created a custom AppRevision entity in the platform module extending Envers' REVINFO with actor_user_id, actor_role, trace_id, and client_ip, driven by an AppRevisionListener that reads from Spring SecurityContext (JWT subject/role) and MDC (traceId/clientIp), falling back to 'SYSTEM' for background jobs. All audit infrastructure (REVINFO, revinfo_seq, 6 audit tables, BRIN index on work_order_aud, least-privilege grants) is created by explicit Flyway V5/V6 migrations so ddl-auto:validate passes. A paginated read-only revisions endpoint at GET /api/v1/work-orders/{id}/revisions is restricted to ADMIN/MANAGER and backed by Envers AuditReader. Tests cover actor attribution, one-revision-per-transaction, shared REVINFO for multi-entity transactions, DEL revision state retention, rollback isolation, BRIN index, password_hash exclusion, and role-based access denial.

## WO-007: User Story: WO-007 - Paginated response envelope with capped size and keyset fallback
- **Status:** completed
- **Commit:** `91abb4c`
- **Files:** 18 (+1372/-15)
- **Duration:** 956ss
- **Approach:** Implemented a paginated response envelope with capped size and keyset fallback. The platform module gains PagedResponse<T> (generic envelope record), PageMeta (with estimated flag for keyset mode), PageLinks (next/prev navigation), PageQuery (size clamped to MAX_SIZE=50), SortAllowList (injection-safe allow-list with id tie-break and fingerprint computation), KeysetCursor (HMAC-SHA256 signed base64url cursor with sort fingerprint), PaginationProperties (@ConfigurationProperties), and PaginationAutoConfiguration (@Bean factory). Two new exceptions (InvalidSortException, InvalidCursorException) are mapped to 400 by GlobalExceptionHandler. WorkOrderController updated to accept page/size/sort/cursor params, auto-switch to keyset links at the configurable threshold (default page 20), and execute keyset queries via JPA Specification with tuple-style WHERE clause (created_at, id) for deterministic ordering.

## WO-008: User Story: WO-008 - Idempotency-Key handling for all mutating endpoints
- **Status:** completed
- **Commit:** `e4ad099`
- **Files:** 18 (+1361/-1)
- **Duration:** 717ss
- **Approach:** Implemented platform-wide idempotency as a Spring OncePerRequestFilter backed by a PostgreSQL table. The filter intercepts all mutating requests (POST, PUT, PATCH, DELETE), validates the Idempotency-Key header format (16-128 chars, allow-listed charset), computes a SHA-256 digest of method+path+body without persisting the raw body, and claims a (key, userId, endpoint) slot via INSERT IN_PROGRESS. On duplicate: replays COMPLETED responses with Idempotent-Replayed header, returns 409 IDEMPOTENCY_CONFLICT on hash mismatch, reclaims stale IN_PROGRESS rows after the configured lease, and returns 503 for live concurrent duplicates. Only 2xx responses are stored (bounded to 256 KB); 4xx/5xx release the slot for legitimate retries. A scheduled purge job deletes expired rows. All state transitions use REQUIRES_NEW transactions for cross-replica visibility.

## WO-108: User Story: WO-108 - Model users, roles, credentials, and token families
- **Status:** completed
- **Commit:** `d940b56`
- **Files:** 17 (+1085/-59)
- **Duration:** 1317ss
- **Approach:** Three Flyway migrations (V8-V10) extend the identity model: V8 alters app_user (nullable password_hash, nullable full_name for graceful migration, adds display_name/external_subject/updated_at, drops simple UNIQUE and creates case-insensitive functional unique index on lower(email)), then creates role_assignment (with ON DELETE RESTRICT FK and CHECK constraint enforcing the 5-role vocabulary), refresh_token_family, and refresh_token tables. V9 extends app_user_aud and creates role_assignment_aud. V10 grants SELECT+INSERT only on role_assignment_aud to the fieldservice runtime role and explicitly revokes UPDATE/DELETE/TRUNCATE. Four JPA entities in com.fieldservice.identity.domain replace the minimal user.domain.AppUser stub; AppUser and RoleAssignment are @Audited with passwordHash @NotAudited. AppRole enum mirrors the 5-role CHECK constraint. UUIDv7 generator used for all primary keys. Four Spring Data repositories expose CRUD operations.

## WO-123: User Story: WO-123 - Declarative work order lifecycle transition table
- **Status:** completed
- **Commit:** `24a5c11`
- **Files:** 19 (+981/-1)
- **Duration:** 1111ss
- **Approach:** Implemented a fully declarative, immutable work-order lifecycle state machine as a package-private Map in WorkOrderTransitionTable, exposed through a public WorkOrderTransitionService @Component. The table encodes 13 transitions across 8 states via (TransitionKey→TransitionDescriptor) entries using Collections.unmodifiableMap(Map.ofEntries(...)). WorkOrderState and WorkOrderEvent enums are internal to the lifecycle package; IllegalWorkOrderTransitionException carries the stable error code WORK_ORDER_ILLEGAL_TRANSITION plus current state, requested event, and legal events. A separate WorkOrderExceptionHandler @RestControllerAdvice in the app module (not platform) handles the exception since platform cannot depend on app. Three new ErrorCode enum values were added. The ADR decision (EN_ROUTE and ON_HOLD may cancel directly) is documented in docs/adr/adr-0007.

## WO-176: User Story: WO-176 - Provider-agnostic AI gateway with resilience and cost guardrails
- **Status:** completed
- **Commit:** `5d20ecd`
- **Files:** 37 (+1820/-1)
- **Duration:** 939ss
- **Approach:** Built the platform's single external-AI egress chokepoint as a new com.fieldservice.aigateway package (api/ public surface + internal/ implementation + fake/ test adapter). The public AiGatewayPort interface exposes complete(), completeStreaming(), and caption(). HttpAiProviderAdapter wraps calls in Resilience4j chain: Retry → Bulkhead → CircuitBreaker, then dispatched via CompletableFuture.supplyAsync on a dedicated virtual-thread executor with TimeLimiter enforcing the 10s budget. EgressAllowList validates the configured base URL against an explicit allow-list before any socket opens. SecretsProvider/EnvironmentSecretsProvider abstracts credential access (AtomicReference for rotation). RedisUsageCapService enforces per-user daily caps (ai:cap:{userId}:{yyyyMMdd} INCR + EXPIRE). FeatureFlagGuardedGateway wraps the adapter and short-circuits to AiUnavailableException when ai.copilot.enabled=false. AiGatewayAutoConfiguration wires everything with @ConditionalOnBean/@ConditionalOnMissingBean so tests get NoOpUsageCapService with no Redis required. Both new exception types live in platform so GlobalExceptionHandler can handle them without cross-module dependency issues.
