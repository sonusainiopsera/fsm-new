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

## WO-183: User Story: WO-183 - Shared component primitive library with named UI states
- **Status:** completed
- **Commit:** `e8c5455`
- **Files:** 45 (+4074/-18)
- **Duration:** 1051ss
- **Approach:** Built the complete WO-183 component primitive library in field-service-web. Each of the 11 primitives lives in its own directory with a co-located CSS Module (var(--token) only) and JSDoc-typed props. DensityContext provides comfortable/compact switching consumed by DataTable, Chip, and FormField. StateSurface unifies all five named UI states behind one parameterised component. DataTable uses a ResizeObserver-based responsive hook (useResponsiveTableMode) to collapse below 768px container width. Modal and DetailDrawer share the same focus-trap pattern with triggerRef focus restoration. ToastProvider uses useReducer to enforce at-most-one per variant for non-danger toasts. Chip carries colour+text+icon for every enum value (BR-34). ScorePresentation uses only --color-neutral-* tokens (BR-33). Mock transport supports configurable latency, error-code injection, and staleness. Catalogue route wired into App.jsx behind a 'catalogue' view state.

## WO-004: User Story: WO-004 - Transactional outbox with atomic state, revision and event write
- **Status:** completed
- **Commit:** `a87accd`
- **Files:** 20 (+1165/-1)
- **Duration:** 902ss
- **Approach:** Flyway V11 creates outbox_event with a jsonb payload, retry/diagnostic columns, and a drain-optimised partial index (idx_outbox_event_drain on created_at WHERE published_at IS NULL). DomainEvent record and DomainEventPublisher interface live in platform.api (public API surface). JpaDomainEventPublisher in platform.outbox uses Propagation.MANDATORY to guarantee publishing can never open its own transaction. The serialisation pipeline runs PiiRedaction (reflective @Restricted fail-fast + @Confidential masking), serialises via a dedicated ObjectMapper with JavaTimeModule, enforces a 64KB size bound, persists OutboxEvent via EntityManager, increments a Micrometer counter tagged by eventType, and emits a structured log line. ErrorCode.PAYLOAD_TOO_LARGE and GlobalExceptionHandler mapping added. OutboxAutoConfiguration registers OutboxProperties via @EnableConfigurationProperties.

## WO-107: User Story: WO-107 - OpenAPI contract publication with snapshot contract tests
- **Status:** completed
- **Commit:** `4db19de`
- **Files:** 12 (+1145/-0)
- **Duration:** 1429ss
- **Approach:** springdoc-openapi 2.6.0 added to app/pom.xml. OpenApiConfiguration defines shared components (ErrorResponse, FieldError, PageMeta, PageLinks, PagedResponse schemas; IdempotencyKey header parameter; bearerAuth security scheme) and adds global bearerAuth security requirement. StandardResponsesCustomizer implements OpenApiCustomizer to inject 400/401/403/404/409/422/429/503 responses and IdempotencyKey header into every operation. SnapshotNormaliser strips volatile fields (info.version → {{version}}, servers[*].url → {{base-url}}, x-generated-on) and sorts all JSON keys deterministically. SnapshotIT seeds snapshot on first run (empty {} seeds to live doc), compares byte-for-byte thereafter, regenerates with -Dupdate-snapshot=true. ContractLintIT enforces five platform-wide lint rules per operation. NormalisationUnitTest covers 9 normalisation unit tests. SecurityConfiguration permits /api-docs/** without bearer token. maven-resources-plugin publishes snapshot as build artifact. swagger-ui disabled by default, enabled only in dev profile.

## WO-109: User Story: WO-109 - Implement login endpoint with BCrypt and account lockout
- **Status:** completed
- **Commit:** `feb69f9`
- **Files:** 25 (+1527/-10)
- **Duration:** 1550ss
- **Approach:** Implemented POST /api/v1/auth/login in AuthController delegating to LoginService which orchestrates: (1) Redis-backed lockout check via LoginAttemptTracker (fail-closed), (2) email lookup, (3) constant-work BCrypt verification including a dummy-hash path for unknown email so timing is indistinguishable across failure types, (4) inactive/grantless checks, (5) RS256 JWT issuance via NimbusJwtEncoder with a startup-generated RSA key pair, (6) refresh token family persistence, (7) LoginAudit + DomainEvent in the same @Transactional(noRollbackFor=...) scope so audit always commits even on auth failure. All auth failure paths collapse to InvalidCredentialsException → 401 INVALID_CREDENTIALS with identical body. Redis dependency failure → AuthDependencyUnavailableException → 503. Access token in body only; refresh handle in HttpOnly Secure SameSite=Strict cookie (path /api/v1/auth, 7-day Max-Age). InMemoryLoginAttemptTracker registered as @ConditionalOnMissingBean fallback for H2 test environments.

## WO-112: User Story: WO-112 - Configure OAuth2 resource server with roles authority mapping
- **Status:** completed
- **Commit:** `4e62fd8`
- **Files:** 21 (+1407/-45)
- **Duration:** 1007ss
- **Approach:** Replaced inline JwtDecoder auto-configuration with JwtDecoderConfig providing a custom NimbusJwtDecoder(JWKSource<SecurityContext>) built from SigningKeyProvider. DelegatingOAuth2TokenValidator composes 60s clock-skew (JwtTimestampValidator), issuer (JwtIssuerValidator), audience (custom), and JTI denylist (JtiDenylistValidator). JtiDenylist does Redis EXISTS per request (fail-closed). JwksCache provides Redis-backed 600s TTL caching with hit/miss/refresh metrics. SecurityConfiguration updated with security headers DSL (HSTS, CSP, nosniff, X-Frame-Options DENY, Referrer-Policy) and wired RestAuthenticationEntryPoint (401) and RestAccessDeniedHandler (403). WebMvcConfig removes XML converters so XXE is impossible. Actuator restricted to health+Prometheus on separate management port. All beans use @ConditionalOnBean(StringRedisTemplate.class) for Redis-dependent components so the H2 test profile (which excludes Redis) continues to work without modification.

## WO-124: User Story: WO-124 - Single transition endpoint for work order state changes
- **Status:** completed
- **Commit:** `7aad0d3`
- **Files:** 10 (+870/-3)
- **Duration:** 1329ss
- **Approach:** Created a single POST /api/v1/work-orders/{id}/transitions endpoint backed by WorkOrderTransitionApplicationService (@Transactional). The application service orchestrates: (1) AccessScope-scoped aggregate load (row-level security), (2) expectedVersion fail-fast check, (3) transition table resolution via existing WorkOrderTransitionService helpers, (4) role enforcement from JWT authorities, (5) ordered guard evaluation (fail-closed), (6) state application via workOrder.applyStateTransition(), (7) entityManager.flush() to catch concurrent-modification ObjectOptimisticLockingFailureException, (8) outbox event via DomainEventPublisher (MANDATORY propagation keeps it atomic), leaving Envers revision to commit automatically. WorkOrderExceptionHandler upgraded with @Order(10) + basePackages restriction to provide work-order-specific error codes (WORK_ORDER_ILLEGAL_TRANSITION with legalNextEvents in fieldErrors, WORK_ORDER_GUARD_REFUSED 422, WORK_ORDER_VERSION_CONFLICT 409) over the GlobalExceptionHandler generic codes. Idempotency handled transparently by the platform IdempotencyFilter.

## WO-148: User Story: WO-148 - Parts catalog, stock locations, and balance foundation
- **Status:** completed
- **Commit:** `31e6bd3`
- **Files:** 17 (+958/-20)
- **Duration:** 894ss
- **Approach:** Expand-only V13 Flyway migration adds missing columns (unit_of_measure, reorder_point, reorder_quantity, active, updated_at on part; location_type, technician_id, updated_at on stock_location; quantity_reserved, updated_at on stock_balance) with named CHECK constraints using NOT VALID + VALIDATE for online migration safety. Envers part_aud and stock_location_aud tables added (stock_balance deliberately not audited — ledger is its audit). Part and StockLocation implement ScopedEntity and are wired into AccessScopePredicateConfiguration with respective EntityScopeSpec beans (PartScopeSpec: CUSTOMER deny-all; StockLocationScopeSpec: TECHNICIAN scoped to own vehicle via technician_id). ScopedRepository used for Part and StockLocation; plain JpaRepository for StockBalance. GET /api/v1/inventory/parts and GET /api/v1/inventory/stock controllers use platform PagedResponse envelope with size hard-capped at 50. Stock endpoint resolves accessible location IDs via ScopedQueryExecutor then applies IN-list predicate on stock_balance, so the scope predicate is in SQL not Java. Seed extended with 10 additional parts, 3 warehouse locations, 5 VEHICLE locations with technician ownership.

## WO-184: User Story: WO-184 - Per-account appearance preference persistence and flash-free restore
- **Status:** completed
- **Commit:** `c49e46a`
- **Files:** 23 (+1214/-24)
- **Duration:** 836ss
- **Approach:** Full-stack appearance preference persistence. API side: expand-only V14 Flyway migration adds nullable appearance_preference column (CHECK constraint to LIGHT/DARK/SYSTEM) to app_user and its Envers audit table. AppearancePreference enum added to domain; AppUser @Enumerated field auto-captured by existing @Audited annotation. UserPreferencesService uses @PreAuthorize + self-scope guard deriving subject exclusively from JWT, writes UserPreferenceAudit record in same @Transactional boundary as entity update. GET/PUT /api/v1/users/me/preferences with Bean Validation enum constraint and FAIL_ON_UNKNOWN_PROPERTIES enforcement. Web side: pre-paint bootstrap in main.jsx synchronously sets data-appearance attribute before ReactDOM.createRoot() (CSP-safe ES module, no unsafe-inline). AppearanceProvider reads DOM attribute on mount, exposes setSetting/toggleAppearance via context, mutates only data-attribute (no stylesheet swap), mirrors to localStorage on change via writeMirror(). API layer in src/api/preferences.js. Telemetry via CustomEvent with role dimension and no PII.

## WO-185: User Story: WO-185 - Application shell with role-derived navigation and code splitting
- **Status:** completed
- **Commit:** `564046a`
- **Files:** 35 (+2201/-3)
- **Duration:** 738ss
- **Approach:** Built the complete application shell as a composition root (AppProviders.jsx) mounting QueryClientProvider, AppearanceProvider, DensityProvider, ToastProvider, AuthProvider, and RouterProvider in documented order. React Router 6 createBrowserRouter with four lazily-loaded surface chunks (dispatch, field, operations, portal) behind React.lazy + Suspense fallbacks bound to the LoadingState skeleton. AppShell.jsx composes a CSS Grid layout with TopBar (banner landmark), Sidebar (navigation landmark), and main (route outlet), all using var(--token) values exclusively. Sidebar filters navigation items from the roles claim for usability only — security note and documentation are explicit that this is never a security control (A01, BR-19). Sidebar collapse state (240px/64px icon rail) is persisted in localStorage with the 768px off-canvas drawer breakpoint winning regardless of preference. fieldServiceWorker.js provides cache-first read access to /api/v1/work-orders for the technician surface only, with a 5-minute max-age and staleness indicator. useNetworkStatus + NetworkOfflineError provides assertOnline() for mutation paths to prevent silent write queuing offline. vite.config.js manualChunks separates all surface chunks and keeps charting libraries in the operations chunk.

## WO-187: User Story: WO-187 - Persona density variants and dual-appearance accessibility gate
- **Status:** completed
- **Commit:** `c26ab68`
- **Files:** 31 (+2364/-8)
- **Duration:** 1193ss
- **Approach:** Four persona density presets implemented in personaDensity.js in field-first order (technician first, BR-35): TECHNICIAN (44px touch targets, single column, 7:1 body contrast, bottom-anchored action, system font), DISPATCHER (32px compact rows, keyboard-first, drawer detail), MANAGER (chart-forward, generous KPI cards), CUSTOMER (most spacious, plain language). DensityContext extended with PersonaDensityProvider that resolves presets from JWT roles array. Complete accessibility gate: contrast.js (WCAG ratio calculator, manifest validator, greyscale DOM assertion helper), adoptionMetrics.js (pure metric functions for the adoption audit), contrastPairings.json (all token pairings with resolved hex values for both appearances). Colour-blind-safe chart series palette (5 entries, each with distinct shape marker + dash pattern per BR-34) and ChartTableEquivalent primitive. Playwright E2E tests in tests/a11y/ (axe dual-appearance, keyboard traversal, greyscale survivability, reduced-motion) and tests/performance/ (CLS/INP vitals, appearance-switch 100ms gate). Design-system adoption audit script (95%/5%/0 thresholds). Vitest scoped to src/ to avoid Playwright spec collision. Stylelint disallows outline:none without visible replacement. All three blocking pipeline steps (test:a11y, test:performance, audit:design-system) added to build:node and documented in docs/ACCESSIBILITY.md + docs/TESTING.md.

## WO-201: User Story: WO-201 - Deterministic Test Fixture And Seed Data Library
- **Status:** completed
- **Commit:** `8eda413`
- **Files:** 11 (+1884/-0)
- **Duration:** 1166ss
- **Approach:** Implemented the full fixture library under com.fieldservice.fixtures in app/src/test/java. DeterministicIds provides a fixed Clock (2025-01-15T09:00:00Z) and a monotonic UUID supplier using UUIDv7 layout with counter-derived deterministic bits so two equal builds produce bit-identical IDs. Five builder classes cover all domain aggregates: UserFixtures (5 roles + RoleAssignment via RoleAssignment.grant()), TechnicianFixtures (Technician + TechnicianCertification with valid/expired/boundary/non-expiring variants), WorkOrderFixtures (all 8 lifecycle states; COMPLETED/CLOSED carry Assignment with non-null releasedAt for lifecycle consistency), InventoryFixtures (Part, StockLocation, StockBalance, SlaPolicy with WAREHOUSE/VEHICLE location types), ScenarioFixtures (dispatchReady + fullLifecycle composed scenarios). Entities without deterministic-id constructors (AppUser, Technician, TechnicianCertification, Part, StockBalance, StockLocation, SlaPolicy) use reflection to override the id field after factory-method construction — this is the standard approach since UuidV7.generate() is static and not injectable. Tests: FixtureBuilderTest (unit, no Spring; 30+ cases covering defaults, overrides, lifecycle consistency, determinism), FixtureHygieneTest (scans fixture sources + seed SQL for non-reserved email domains, phone ranges, plaintext passwords), FixturePersistenceIT (Testcontainers PostgreSQL 16; persists dispatch-ready and full-lifecycle scenarios via JPA, asserts constraints; runs seed-core.sql twice to prove idempotency). Seed SQL uses ON CONFLICT DO NOTHING and distinct UUID prefix 00000000-0000-7011..7016 namespace.

## WO-005: User Story: WO-005 - Worker outbox drain with SKIP LOCKED and idempotent dispatch
- **Status:** completed
- **Commit:** `ea65556`
- **Files:** 14 (+1334/-15)
- **Duration:** 1079ss
- **Approach:** OutboxPoller is placed in platform.outbox (same package as the package-private OutboxEvent JPA entity) and annotated @Profile(worker) + @Scheduled(fixedDelay=500ms). It opens a TransactionTemplate per poll, claims up to batchSize rows using native SELECT FOR UPDATE SKIP LOCKED ordered by created_at, dispatches each event inside the same transaction, and marks published_at on success or applies jittered exponential backoff on failure. After maxAttempts failures the event is dead-lettered and excluded from future claims. The ConsumerIdempotencyGuard provides INSERT...ON CONFLICT DO NOTHING on processed_event. JdbcSchedulingLock provides a conditional UPSERT-based distributed lock with all timestamps evaluated by the database. OutboxPollerMetrics registers Micrometer gauges, timers, and counters. OutboxDrainConfiguration enables scheduling only on the worker profile. OutboxProperties was extended with batchSize, maxAttempts, backoff, jitter, and dispatchTimeout. A default Clock.systemUTC() bean is registered in OutboxAutoConfiguration and overridable in tests.

## WO-110: User Story: WO-110 - Rotate refresh tokens with family reuse detection
- **Status:** completed
- **Commit:** `2d4f97e`
- **Files:** 13 (+1337/-16)
- **Duration:** 1238ss
- **Approach:** Atomicity is achieved via a conditional native UPDATE (consumed_at IS NULL AND revoked_at IS NULL) in RefreshTokenRepository.consumeToken() whose affected-row count distinguishes valid use (1) from reuse/unknown (0). On 0 rows the service diagnoses the cause: unknown hash (no family), already-revoked family (SIEM dedup, no new event), or first reuse (revoke family + all tokens + publish REFRESH_TOKEN_REUSE event via outbox). On 1 row the service enforces family expiry via the DB-stored expiresAt field (never recalculated), checks the user is active, issues new tokens under the same family, persists a new RefreshToken with the original expiresAt (rotation cannot extend the family), and publishes REFRESH_TOKEN_ROTATED. The handle is read exclusively from the HttpOnly cookie by @CookieValue; missing/blank → 401 before any service call. Format is validated against a 43-char base64url regex before any DB access. AuthController catches InvalidCredentialsException from RefreshTokenService and returns REAUTHENTICATION_REQUIRED with a cookie-clearing Set-Cookie. V16 migration adds expires_at to refresh_token_family with backfill. RefreshTokenFamily.open() now takes an explicit expiresAt. SecurityEventPublisher uses Propagation.MANDATORY so events and revocation always commit together.

## WO-113: User Story: WO-113 - Enforce mandatory row-scope AccessScope query predicates
- **Status:** completed
- **Commit:** `d2b542d`
- **Files:** 11 (+632/-38)
- **Duration:** 952ss
- **Approach:** The WO-009 platform infrastructure (AccessScope, AccessScopeResolver, ScopedRepository, ScopedQueryExecutor, EntityScopeSpec, AccessScopePredicateFactory, WorkOrderScopeSpec) was already complete. This WO adds the four missing pieces: (1) ScopeDenialTranslator encodes the single ratified 403/404 rule — CUSTOMER cross-account probes throw NotFoundException (→ 404 byte-identical for both nonexistent and out-of-scope IDs), all other roles throw ScopedAccessDeniedException (→ 403); every denial also emits a structured audit log and increments security.scope.denial with role/resource/outcome tags. (2) CurrentPrincipal interface extracted from RequestScopedAccessScope for stable typing. (3) Micrometer counters added to RestAccessDeniedHandler (type=ROLE_DENIED) and GlobalExceptionHandler (type=SCOPE_DENIAL). (4) WorkOrderController.getWorkOrder() wired to ScopeDenialTranslator instead of hard-coding ScopedAccessDeniedException. CrossRoleProbeMatrixTest updated: CUSTOMER cross-account probe assertions changed from 403 → 404. ScopedRepositoryFitnessTest adds ArchUnit positive rule plus non-compliant fixture proving the rule fires. ScopeDenialTranslatorUnitTest unit-tests all deny branches without Spring context. SIEM alerting rules defined in siem-alerting-rules.yml.

## WO-114: User Story: WO-114 - Author and enforce RBAC method-security role matrix
- **Status:** completed
- **Commit:** `5fab408`
- **Files:** 8 (+655/-0)
- **Duration:** 1119ss
- **Approach:** WO-114 adds the RBAC method-security layer on top of the existing WO-113 row-scope infrastructure. The implementation follows the WO's 'author matrix first, then encode it' approach: (1) docs/security/rbac-matrix.md is the ratified human-readable matrix with reviewer/date sign-off fields; (2) app/src/test/resources/security/rbac-matrix.yml is the machine-readable form driving parameterized tests; (3) @PreAuthorize was added to WorkOrderTransitionApplicationService.apply() (ADMIN, DISPATCHER, MANAGER, TECHNICIAN) and WorkOrderRevisionService.getRevisions() (ADMIN, MANAGER) — the two service methods that lacked service-layer annotations; (4) SecurityConfiguration already had @EnableMethodSecurity; UserPreferencesService already had @PreAuthorize('isAuthenticated()'); (5) MethodSecurityTest.java is the ArchUnit fitness test checking all @Service classes in com.fieldservice.workorder have @PreAuthorize on public methods; (6) UnprotectedServiceFixture.java proves the rule fires; (7) RbacMatrixTest.java is the parameterized integration test reading from rbac-matrix.yml — covers get_work_order_revisions (all 5 roles, deterministic 200/403 outcomes) + CUSTOMER deny + DISPATCHER permit for apply_work_order_transition + direct service-layer invocation tests proving defence-in-depth; (8) .semgrep/authorization-rules.yml adds the pipeline gate outside the JVM.

## WO-115: User Story: WO-115 - Issue single-use IP-bound SSE stream tickets
- **Status:** completed
- **Commit:** `97030eb`
- **Files:** 9 (+1139/-1)
- **Duration:** 1232ss
- **Approach:** The stream ticket lifecycle is implemented as four new classes plus modifications to AuthController and SecurityConfiguration. StreamTicketStore (@Component @ConditionalOnBean(StringRedisTemplate.class)) stores a Redis HASH keyed on SHA-256(ticketValue) with a 60-second TTL; the plaintext ticket never touches Redis. Atomic single-use redemption uses a Lua script (HGETALL + DEL in one round-trip) via DefaultRedisScript<List>. StreamTicketService injects optional beans via List<T> pattern (same as JtiDenylist in JwtDecoderConfig), generates 256-bit SecureRandom tickets (base64url, 43 chars), validates IP binding, JTI denylist membership, and account active state at redemption; all failures collapse to StreamTicketRedeemException (generic 401 contract). StreamTicketAuthFilter (NOT @Component) extends OncePerRequestFilter and is created inline inside streamFilterChain() to avoid Spring Boot auto-registration. SecurityConfiguration gains @Order(1) streamFilterChain scoped to /api/v1/streams/**; the existing JWT chain moves to @Order(2). AuthController adds POST /api/v1/auth/stream-ticket with @PreAuthorize('isAuthenticated()'). application.yml configures server.tomcat.accesslog.pattern using %U (URI without query string) to prevent ticket leakage in access logs. The log-capture integration test uses a Logback ListAppender attached to the root logger to assert the ticket value is absent from every log line across the issue + redeem + replay flow.

## WO-125: User Story: WO-125 - Business precondition guards for lifecycle transitions
- **Status:** completed
- **Commit:** `094acc7`
- **Files:** 21 (+1184/-15)
- **Duration:** 1380ss
- **Approach:** N/A

## WO-126: User Story: WO-126 - Controlled hold reason vocabulary and resume handling
- **Status:** completed
- **Commit:** `016124b`
- **Files:** 21 (+946/-40)
- **Duration:** 1067ss
- **Approach:** N/A

## WO-127: User Story: WO-127 - Paginated work order search with row-scoped access
- **Status:** completed
- **Commit:** `c53e335`
- **Files:** 10 (+765/-61)
- **Duration:** 844ss
- **Approach:** Implemented filterable, paginated, row-scoped work order collection. WorkOrderSearchCriteria record holds all optional filter fields; WorkOrderSearchService builds a Specification<WorkOrder> by ANDing non-null criteria. The controller enforces server-side size cap (max 50), validates sort via SortAllowList.ALLOW_LIST (unknown field → 400), computes SHA-256 ETag over id:version tuples for conditional GET (304 support), and switches from offset to keyset pagination at offsetThreshold (page 20). Row scope is enforced by ScopedQueryExecutor which ANDs the AccessScope predicate into every query and count, so out-of-scope rows are never loaded. Board responses use WorkOrderBoardRow records, not JPA entities. V19 migration adds response_deadline, resolution_deadline, at_risk columns plus composite indexes and a partial at-risk index.

## WO-149: User Story: WO-149 - Atomic parts consumption enforcing non-negative stock
- **Status:** completed
- **Commit:** `3c5f848`
- **Files:** 23 (+1572/-1)
- **Duration:** 892ss
- **Approach:** Implemented BR-16/BR-17 (stock never negative, atomic consumption) via a conditional UPDATE primitive (quantity_on_hand >= :qty predicate). StockMovementService is the sole writer of stock tables. Two-pass consume: pass 1 scans all balance rows for shortfalls before any mutation; pass 2 applies conditionalDecrement, persists StockLedger + WorkOrderPart + Envers audit, publishes PARTS_CONSUMED outbox event — all in one @Transactional boundary. Race loss in pass 2 also throws InsufficientStockException, rolling back all decrements already applied. WorkOrderPartsController lives in workorder.web (not inventory.web) and delegates to StockMovementService interface. ADR-0009 records 422 vs 409 resolution.

## WO-186: User Story: WO-186 - Shared data layer with error mapping, refresh, and SSE ticket
- **Status:** completed
- **Commit:** `03c09e0`
- **Files:** 25 (+2492/-14)
- **Duration:** 828ss
- **Approach:** Implemented the complete TanStack Query–based data layer as nine focused modules plus tests, mocks, a code generator, and documentation. The core invariants are: (1) 4xx responses are never retried (A10 — retrying a 422 guard refusal fails open), (2) the access token lives in module scope only via tokenStore.js with a single-flight refresh guard, (3) SSE authentication uses single-use stream tickets so the bearer token never appears in a URL. Each module has a single responsibility: tokenStore for in-memory token lifecycle, http.js for the fetch wrapper with 401 intercept, errors.js for envelope normalization with retryable flag, queryClient.js for the configured singleton, stateMapping.js for error→named-state translation, pagination.js for envelope parsing and size clamping, useConditionalQuery.js for ETag/304 conditional polling, sseClient.js for the ticket→EventSource reconnect lifecycle, and eventKeyMap.js for SSE→query-key invalidation. AppProviders.jsx updated to import the singleton queryClient. The generate-api-client.mjs script reads the committed OpenAPI snapshot and emits JSDoc typedefs and endpoint accessors with boundary _assertShape() validation, wired into build:node as the Q6 drift-detection gate.

## WO-202: User Story: WO-202 - Testcontainers Postgres Integration Test Harness
- **Status:** completed
- **Commit:** `355ed44`
- **Files:** 11 (+1010/-0)
- **Duration:** 852ss
- **Approach:** Built the integration test harness bottom-up: (1) app/pom.xml — added Redis testcontainer dep, JaCoCo plugin with prepare-agent/prepare-agent-integration/merge/report-aggregate/check executions (80% line threshold), and failsafe execution binding; (2) PostgresContainerSupport — singleton static PostgreSQL 16 container with withReuse(true) guarded by the ~/.testcontainers.properties flag, DynamicPropertySource wiring all datasource/flyway/dialect/security properties; (3) AbstractIntegrationTest — @Tag(integration) @SpringBootTest @AutoConfigureMockMvc base class extending the container support, autowired mockMvc/dataSource/entityManager/txManager/tx; (4) DatabaseCleaner — Spring component with MUTABLE_TABLES ordered list excluding sla_policy and hold_reason, TRUNCATE … RESTART IDENTITY CASCADE in one statement; (5) AuditAssertions — JDBC-based findRevisions/assertRevisionCount/assertLatestRevisionType using raw _AUD + REVINFO queries to avoid Envers API coupling; (6) OutboxAssertions — findByAggregateId/assertExactlyOne/assertNone against outbox_event; (7) RedisContainerSupport — opt-in subclass adding singleton Redis 7 GenericContainer with DynamicPropertySource; (8) AuditSchemaShapeIT — asserts REVINFO, revinfo_seq, and all 12 *_aud tables exist; (9) WorkOrderLifecycleIT — real-commit e2e: creates WorkOrder, transitions via HTTP POST, asserts 2 Envers revisions and one outbox event, proves isolation; (10) DatabaseCleanerTest — unit tests for table ordering and reference-data exclusion; (11) app/TESTING.md — appended harness documentation covering quick start, base classes, isolation strategies, assertion helpers, container reuse, DB inspection, and coverage.

## WO-111: User Story: WO-111 - Revoke session on logout with jti denylist
- **Status:** completed
- **Commit:** `68e4172`
- **Files:** 6 (+847/-4)
- **Duration:** 526ss
- **Approach:** Implemented the logout endpoint with strict ordering semantics: (1) LogoutService uses TransactionTemplate to revoke the refresh-token family and all its tokens with reason USER_LOGOUT inside a transaction, publishing a USER_LOGGED_OUT audit event via SecurityEventPublisher; (2) after the transaction commits, JtiDenylist.deny() is called with the token's expiry to insert the JTI with its residual TTL — JtiDenylist already floors the TTL at zero so expired tokens are a no-op; (3) on denylist failure, REVOKED_DENYLIST_FAILED is returned so the controller responds 503 while the committed family revocation stands; all idempotent paths (null handle, malformed handle, unknown handle, already-revoked family) return NO_OP → 204 without any exception. AuthController adds POST /api/v1/auth/logout extracting jti and exp from JwtAuthenticationToken when present. SecurityConfiguration adds /api/v1/auth/logout to permitAll so the endpoint works without a valid bearer. SecurityEventPublisher gets publishLogout() emitting USER_LOGGED_OUT with only userId/familyId/traceId — no token material. clearRefreshCookie() was refactored out of reauthRequiredResponse() so both logout and error paths use identical cookie attributes.

## WO-116: User Story: WO-116 - Build sign-in screen with in-memory token custody
- **Status:** completed
- **Commit:** `876e558`
- **Files:** 9 (+1392/-118)
- **Duration:** 1041ss
- **Approach:** Built the full sign-in page on top of the existing tokenStore and httpClient infrastructure. SignIn.jsx delivers the ratified component hierarchy (appearance toggle, split brand/auth layout, email input, password+show-password, remember-device switch, forgot-password link, generic error alert, submit with loading state, SSO behind disabled feature flag, footer notice). useSignIn.js wraps TanStack Query useMutation with boundary validation of the login response before it reaches tokenStore. AuthContext.js was upgraded with a boot-time silent refresh (via tokenStore.refresh singleton so concurrent callers share one promise), isBootComplete state, and a tokenStore subscriber that syncs isAuthenticated on mid-session token clears. AppShell.jsx redirects to /sign-in once isBootComplete and !isAuthenticated. ESLint restricted-properties rule blocks localStorage/sessionStorage in all auth and token-handling modules. Mock handlers updated with user object in the 200 success response and dedicated auth fixture helpers for 400/401/429/503.

## WO-117: User Story: WO-117 - Catalog module: customer, site and asset reference data
- **Status:** completed
- **Commit:** `046e827`
- **Files:** 24 (+1865/-27)
- **Duration:** 1461ss
- **Approach:** Added catalog foundation on top of the existing entity stubs. V21 migration is expand-only (ALTER TABLE ADD COLUMN IF NOT EXISTS) adding active/deactivated_at/account_code/legal_name/site_code/asset_tag columns to customer, site and asset tables, plus customer_aud and asset_aud Envers tables and ALTER TABLE site_aud to add new columns. CustomerAccount and Asset entities gained @Audited. Asset gained ScopedEntity, AssetScopeSpec uses a JPQL subquery to join through site.customerId for CUSTOMER principals. AccessScopePredicateConfiguration updated to include Asset.class. ScopedRepository impls created for all three entities. CatalogService owns @PreAuthorize on every public method, hierarchy guards that refuse creation under inactive parents with 422 via BusinessGuardException, cascade deactivation propagating down customer→site→asset, and transactional outbox publication of CustomerChanged/SiteChanged/AssetChanged. CustomerController handles GET/POST on /api/v1/customers and nested sites; SiteController handles GET/DELETE on /api/v1/sites and nested assets. All DTOs use @JsonIgnoreProperties(ignoreUnknown=false) for FAIL_ON_UNKNOWN_PROPERTIES. seed-core.sql extended with 4 customers (1 inactive), 8 sites (1 inactive), 22 assets (4 inactive including deactivated-site cascade examples).

## WO-142: User Story: WO-142 - Runtime-configurable SLA policy and deadline derivation
- **Status:** completed
- **Commit:** `6ef6fca`
- **Files:** 26 (+1091/-12)
- **Duration:** 818ss
- **Approach:** N/A

## WO-150: User Story: WO-150 - Append-only stock ledger and reconciliation integrity check
- **Status:** completed
- **Commit:** `f0c4c51`
- **Files:** 21 (+1603/-37)
- **Duration:** 851ss
- **Approach:** Extended stock_ledger table via expand-only Flyway migration V23 adding 11 rich movement columns (from_location_id, movement_type, delta_quantity, resulting_quantity, reason_code, work_order_id, actor_user_id, correlation_id, idempotency_key, occurred_at) plus backfill from legacy columns. Append-only enforcement is layered: REVOKE UPDATE/DELETE in the migration, an ArchUnit rule blocking delete paths at build time, and Propagation.MANDATORY on LedgerWriteService. A scheduled reconciliation sweep (worker profile, PostgreSQL advisory lock, configurable interval) compares SUM(delta_quantity) per (part, location) against stock_balance and emits Micrometer counters and ALERT logs on discrepancy. A completeness gauge tracks what fraction of closed work orders in a 24-hour window have ledger entries or are marked no_parts_required. The movement query API exposes paginated filtered reads at /api/v1/inventory/movements with TECHNICIAN scoping to their assigned location.

## WO-161: User Story: WO-161 - Analytics read-model substrate with debounced outbox consumer
- **Status:** completed
- **Commit:** `8b2c06a`
- **Files:** 24 (+2057/-0)
- **Duration:** 1036ss
- **Approach:** Built the analytics read-model substrate bottom-up: V24 Flyway migration creates kpi_projection and processed_event; the analytics module is split into a public API package (KpiProjection DTO + KpiProjectionQuery port) and a package-private internal package. KpiOutboxConsumer handles 6 event types via EventHandler adapters registered in AnalyticsConfiguration, enforces idempotency with an insertIfAbsent native query, and enqueues metric keys into MetricDebounceRegistry (ConcurrentHashMap with 15-second window). AnalyticsWorker (worker-profile-only) drains the registry every second under a SchedulingLock and drives KpiProjectionRefreshService. KpiProjectionCache is null-safe for Redis absence/outage, uses version-qualified keys with 30s TTL and a pointer key for degraded-path lookups. DegradationPolicy translates DB or Redis failures to degraded KpiProjection responses without ever throwing to the caller. AnalyticsMetrics exports staleness, refresh-lag and cache-hit-ratio Gauges plus refresh Counters per metric. An optional replica DataSource and analyticsJdbcTemplate provide the KpiAggregator SPI with replica-routed read access for WO-066 through WO-069. Seven ArchUnit rules guard module isolation in both directions.

## WO-188: User Story: WO-188 - Data classification registry with per-entity tier metadata
- **Status:** completed
- **Commit:** `f271761`
- **Files:** 25 (+1384/-1)
- **Duration:** 1283ss
- **Approach:** Created a new privacy Maven module (inheriting the parent pom, depending on platform + hibernate-envers + spring-boot-starter-cache) registered in parent modules and as an app dependency. The module is split into a public api package (ClassificationTier enum, @DataClassification annotation, ClassificationView record, ClassificationRegistry read-only interface, ClassificationService mutation+paging extension) and a package-private internal package (DataClassificationEntity @Audited JPA entity, DataClassificationRepository Spring Data, ClassificationRegistryImpl with @Cacheable/@CacheEvict, ClassificationConsistencyCheck ApplicationRunner gated by @ConditionalOnProperty). The web package provides ClassificationController at /api/v1/privacy/classifications with @PreAuthorize('hasAnyRole(PRIVACY_ADMIN,ADMIN)'). V25 Flyway migration creates data_classification with CHECK constraint and functional unique index, creates data_classification_aud for Envers, extends role_assignment CHECK to include PRIVACY_ADMIN, and seeds the full data-classification taxonomy (RESTRICTED/CONFIDENTIAL/INTERNAL/PUBLIC). @DataClassification applied to AppUser, WorkOrder, Technician, SlaPolicy covering all four tiers. PRIVACY_ADMIN added to AppRole enum.

## WO-195: User Story: WO-195 - Resilient notification delivery port with degraded in-app fallback
- **Status:** completed
- **Commit:** `a21eec7`
- **Files:** 32 (+1596/-0)
- **Duration:** 847ss
- **Approach:** N/A

## WO-200: User Story: WO-200 - ArchUnit Fitness Tests For Module And Security Boundaries
- **Status:** completed
- **Commit:** `8a0a9b6`
- **Files:** 9 (+661/-0)
- **Duration:** 860ss
- **Approach:** Added three new ArchUnit test classes in app/src/test/java/com/fieldservice/app/arch/ that enforce architecture fitness rules: (1) LayeredArchitectureTest enforces the controller→service→repository layering rule and freezes 8 pre-existing legacy violations using ArchUnit's ignoreDependency() API with dated justifications; (2) InjectionAndCryptoRulesTest implements CRYPTO-1 (ban raw MessageDigest outside approved packages), CRYPTO-2 (no deprecated MD5/SHA-1/DES algorithms — enforced via an approved-package allowlist since ArchUnit cannot inspect method argument values), and INJECT-1 (ban direct JDBC Statement.execute/executeQuery/executeUpdate calls); (3) DtoBoundaryTest uses a custom ArchCondition to prevent @Entity types from appearing in @RestController method signatures. Each test class includes a self-test method importing a deliberately violating fixture class that proves the rule fires. Pre-existing tests (ModuleBoundaryTest, MethodSecurityTest, ScopedRepositoryFitnessTest) already cover module encapsulation, @PreAuthorize coverage, and row-scope repository rules.

## WO-203: User Story: WO-203 - Role And Row-Scope Access Control Test Matrix
- **Status:** completed
- **Commit:** `9c3035f`
- **Files:** 7 (+1116/-0)
- **Duration:** 1153ss
- **Approach:** Created a declarative access-control test matrix as the single source of truth (AccessControlMatrix.java, 29 entries). AccessControlMatrixTest expands the matrix × 6 role probes into 174 parameterized cases asserting exactly 401/403/permitted for each cell. EndpointCoverageTest uses Spring's RequestMappingHandlerMapping to enforce completeness: any registered non-exempt endpoint without a matrix row fails the build, and stale matrix rows are also detected. TokenShapeNegativeTest covers valid-JWT-but-wrong-role-casing scenarios (lowercase and unknown role strings → 403, not 401). MassAssignmentTest posts bodies with unknown fields to mutating endpoints and asserts 400 plus a COUNT(*) proof that no partial row was written. Pre-existing tests (CrossRoleProbeMatrixTest, RbacMatrixTest, ScopedQuerySqlPredicateTest, RolesClaimAuthorityConverterTest) are preserved without duplication. rbac-matrix.yml was expanded from 4 to 9 service-layer operations. TESTING.md received a new 'Access Control Test Matrix' section.

## WO-204: User Story: WO-204 - P0 API Contract And Envelope Conformance Suite
- **Status:** completed
- **Commit:** `7a4548f`
- **Files:** 7 (+1905/-0)
- **Duration:** 925ss
- **Approach:** Built a layered contract conformance suite under com.fieldservice.contract. First extracted all envelope/error/no-internals assertions into a reusable ApiAssertions helper. Then wrote one @Testcontainers IT per P0 endpoint group, each owning an isolated PostgreSQL 16 database with unique withDatabaseName to support parallel execution. TransitionContractIT adds an Envers revision-count assertion (SELECT COUNT(*) FROM work_order_aud) on idempotent replay. PartsConsumptionContractIT adds a stock_ledger row-count assertion on idempotent replay plus a stock_balance unchanged assertion on 422 refusal. WorkOrderContractIT adds a pagination-stability proof using a CountDownLatch background thread that inserts/updates rows during page iteration. OpenApiConformanceTest validates live response payloads against the /api-docs schema using Jackson structural checks. Recommendations and assignment groups are deferred — those HTTP endpoints are blocked by WO-186 which is not yet implemented.

## WO-118: User Story: WO-118 - Workforce module: technician profiles, skills and availability
- **Status:** completed
- **Commit:** `7a00874`
- **Files:** 34 (+1793/-7)
- **Duration:** 933ss
- **Approach:** N/A

## WO-128: User Story: WO-128 - Work order creation with automatic deadline derivation
- **Status:** completed
- **Commit:** `61ba666`
- **Files:** 13 (+708/-42)
- **Duration:** 762ss
- **Approach:** Expanded the existing WorkOrderCreationService skeleton to implement full AC-compliant work order creation: replaced the manual-reference, assignee-accepting request DTO with a strict enum-bound record (customerId, siteId, assetId, faultDescription 10-4000, priority enum, optional certifications and parts); auto-generates the human-readable reference from a DB sequence (WO-{seq}); validates site.customerId == request.customerId (422 SITE_CUSTOMER_MISMATCH) and asset.siteId == request.siteId (422 ASSET_SITE_MISMATCH); resolves the active SLA policy via the existing SlaPolicyProvider cache and snapshots its id on the work order (applied_sla_policy_id); always creates state=NEW; adds Location header on 201. Idempotency-Key is handled transparently by the platform IdempotencyFilter. A V29 migration adds the applied_sla_policy_id column and the work_order_ref_seq sequence. CUSTOMER portal submissions are gated by a priority ceiling constant (HIGH) enforced in the service.

## WO-153: User Story: WO-153 - Stock position screens with low-stock and staleness states
- **Status:** completed
- **Commit:** `99dfbcb`
- **Files:** 27 (+3144/-2)
- **Duration:** 873ss
- **Approach:** Built the inventory React surfaces in order: (1) utility libs — idempotency.js generates per-attempt UUIDs reused on retries, freshness.js evaluates the 60-second staleness budget for BR-15; (2) typed API client inventory.js covering WO-053/054/055/056 endpoints with ETag-conditional GET and idempotency-keyed POSTs; (3) PartsLoggingPanel mobile-first (360 px, 44 px targets) with debounced part search, multi-line staging, inline quantity validation, 422 INSUFFICIENT_STOCK per-line detail + awaiting-parts hold action, network-failure not-connected state, and idempotent submission; (4) Inventory route module — StockPositionsPage with DataTable (density control, sticky header, tabular figures), LowStockPage with text+icon+shape multi-cue indicators (BR-32/BR-34), MovementHistoryDrawer with ETag-conditional polling and stale state; (5) router.jsx updated with lazy /inventory/* route; (6) mock handlers extended with all inventory routes; (7) JSON fixtures including stale as-of and 422 insufficient-stock responses; (8) RTL component tests and Playwright E2E spec.

## WO-164: User Story: WO-164 - First-time fix rate with matured cohort linkage
- **Status:** completed
- **Commit:** `3220d62`
- **Files:** 18 (+1690/-4)
- **Duration:** 1351ss
- **Approach:** N/A

## WO-165: User Story: WO-165 - Backlog and workload balance guardrail projections
- **Status:** completed
- **Commit:** `9992959`
- **Files:** 11 (+1548/-5)
- **Duration:** 1030ss
- **Approach:** N/A

## WO-169: User Story: WO-169 - Customer account linkage and row-scope access predicate
- **Status:** completed
- **Commit:** `3809cf5`
- **Files:** 18 (+1586/-3)
- **Duration:** 719ss
- **Approach:** N/A

## WO-189: User Story: WO-189 - Configurable retention schedule with automated purge sweep
- **Status:** completed
- **Commit:** `afba59a`
- **Files:** 31 (+1813/-7)
- **Duration:** 992ss
- **Approach:** N/A
