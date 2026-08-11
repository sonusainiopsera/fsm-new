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

## WO-183: User Story: WO-183 - Shared component primitive library with named UI states
- **Status:** completed
- **Commit:** `a747655`
- **Files:** 36 (+3022/-10)
- **Duration:** 897ss
- **Approach:** Implemented the frozen Phase 2 primitive inventory as a flat component tree under field-service-web/src/components/. Each primitive uses var(--token-*) exclusively for styling — enforced by the existing no-hardcoded-visual-literals ESLint rule. DensityContext (comfortable/compact) is a React context consumed by DataTable, Chip, and FormField. Modal and DetailDrawer share the same focus-trap pattern (Tab cycle, Escape, scrim click, trigger-restore on close). StateSurface drives all five named state exports from a single implementation. ToastProvider maintains separate polite/assertive DOM regions and throttles non-danger toasts to 1 visible. ScorePresentation is fully monochrome (BR-33). Chips carry icon+text+color for greyscale support (BR-34). The mock transport is fixture-backed with configurable latency, error-code injection, and staleness. The catalogue route is wired into main.jsx behind VITE_CATALOGUE=true and a build:catalogue npm script was added for CI smoke-checking.

## WO-004: User Story: WO-004 - Transactional outbox with atomic state, revision and event write
- **Status:** completed
- **Commit:** `683d58a`
- **Files:** 19 (+1301/-0)
- **Duration:** 747ss
- **Approach:** Implemented the transactional outbox seam as a two-layer design. The platform module public API exposes DomainEvent (record with eventId/eventType/aggregateType/aggregateId/occurredAt/traceId/actorUserId/payload) and DomainEventPublisher (interface). The platform module outbox package contains OutboxEvent (JPA entity with @JdbcTypeCode(JSON) for jsonb column), JpaDomainEventPublisher (@Component, @Transactional(MANDATORY) — throws IllegalTransactionStateException if no active transaction), PayloadSerializer (isolated ObjectMapper with ISO-8601 dates and size bounding), PiiRedactionUtility (reflection-based @Restricted/@Confidential check), and the two annotation types. Flyway V13 creates outbox_event with a partial drain index (WHERE published_at IS NULL). The app module adds WorkOrderStateChangedPayload as the prototype payload record and WorkOrderEventFixtureBuilder for reuse by WO-005.

## WO-107: User Story: WO-107 - OpenAPI contract publication with snapshot contract tests
- **Status:** completed
- **Commit:** `e15562b`
- **Files:** 9 (+994/-0)
- **Duration:** 963ss
- **Approach:** Added springdoc-openapi-starter-webmvc-ui 2.8.6 to the app module. Created OpenApiConfiguration (@Configuration @Bean) publishing the OpenAPI 3 document: API info with configurable version (API_VERSION env var), single server entry (API_PUBLIC_BASE_URL), bearer JWT global security scheme with a named PUBLIC_OPERATION_IDS allow-list, and shared component schemas for ErrorResponse, FieldError, PageMeta, PageLinks, PagedResponse, plus a reusable IdempotencyKey header parameter. Created StandardErrorResponsesCustomizer (OpenApiCustomizer @Component) that attaches the standard 400/401/403/404/409/422/429/503 responses to every operation using the ErrorResponse $ref. Created OpenApiNormalizer test utility that strips volatile fields (info.version → SNAPSHOT, servers[].url → PLACEHOLDER) and sorts all JSON object keys deterministically for stable snapshots. OpenApiContractLintTest boots the api profile and asserts: bearer security on all non-public operations, 4xx responses on all operations, 401/403 on all operations, 409 on mutating operations, shared schemas present, swagger-ui disabled by default, no forbidden substrings. OpenApiSnapshotTest generates the normalised snapshot, writes it to target/openapi/field-service-api.json as a build artifact, and compares against src/test/resources/openapi/snapshot.json. A {} bootstrap placeholder allows the first run to auto-generate the snapshot. springdoc configuration added to application.yml (api-docs enabled, swagger-ui disabled by default via SPRINGDOC_SWAGGER_UI_ENABLED env var). OPENAPI.md documents conventions, how to add an endpoint, and how to regenerate the snapshot.

## WO-109: User Story: WO-109 - Implement login endpoint with BCrypt and account lockout
- **Status:** completed
- **Commit:** `ebc6b05`
- **Files:** 20 (+1617/-0)
- **Duration:** 1315ss
- **Approach:** Implemented POST /api/v1/auth/login with three layered interfaces: LoginAttemptTracker (Redis-backed production / in-memory test fallback using @ConditionalOnBean), TokenIssuer (RS256 JWT via Nimbus JOSE+JWT with ephemeral RSA-2048 key generated at startup pending WO-013), and PasswordEncoder (DelegatingPasswordEncoder wrapping BCryptPasswordEncoder at strength 12). LoginService orchestrates lockout check, constant-work dummy-hash BCrypt path for unknown emails, active/grantless guards, token issuance, RefreshTokenFamily+RefreshToken persistence, DomainEventPublisher event within the same @Transactional boundary. AuthController is a thin HTTP adapter with Bean Validation, unknown-property rejection, uniform 401 for all auth failures, and Set-Cookie with HttpOnly/Secure/SameSite=Strict. JwtSigningKeyConfig generates an ephemeral RSA-2048 key pair at startup (warning logged). AuthProperties @ConfigurationProperties binds all auth settings from app.auth.* in application.yml. ErrorEnvelope.Code gained INVALID_CREDENTIALS and AUTH_DEPENDENCY_UNAVAILABLE. Unit tests use in-memory fakes; integration tests use AbstractIntegrationTest (PostgreSQL Testcontainers); lockout Redis tests use a separate login-test profile with both PostgreSQL and Redis containers.

## WO-112: User Story: WO-112 - Configure OAuth2 resource server with roles authority mapping
- **Status:** completed
- **Commit:** `ea6ee74`
- **Files:** 24 (+1518/-136)
- **Duration:** 1650ss
- **Approach:** Replaced SecurityConfiguration with SecurityFilterChainConfig in identity/config, added complete OAuth2 resource server chain. Created identity/token package with SigningKeyProvider interface (EphemeralRsaSigningKeyProvider wraps JwtSigningKeyConfig key pair), JtiDenylist (Redis EXISTS fail-closed + in-memory fallback), JwksCache (Redis 600s TTL with Micrometer counters + direct fallback), and DenylistOAuth2TokenValidator. JwtDecoderConfig wires NimbusJwtDecoder from JwksCache with DelegatingOAuth2TokenValidator composing timestamp (60s skew), issuer, audience and denylist validators. Security headers DSL adds HSTS/CSP/nosniff/frame-deny/referrer-policy. RestAuthenticationEntryPoint and RestAccessDeniedHandler render shared ErrorEnvelope. Test infrastructure uses programmatically-generated ephemeral RSA key pair (TestRsaKeyPair) with TestTokenMinter minting all token variants offline.

## WO-124: User Story: WO-124 - Single transition endpoint for work order state changes
- **Status:** completed
- **Commit:** `36146c5`
- **Files:** 13 (+1051/-37)
- **Duration:** 1200ss
- **Approach:** Single POST /api/v1/work-orders/{id}/transitions endpoint wired through a new applyTransition() service method. Loads via ScopedQueryExecutor (row-scope enforcement; absent == out-of-scope == 403 non-disclosure). Pre-checks client-supplied expectedVersion before guard evaluation. Guards evaluated fail-closed: any exception becomes GuardRefusedException. JPA flush inside @Transactional catches ObjectOptimisticLockingFailureException and rethrows as WorkOrderVersionConflictException. Outbox event published (MANDATORY propagation) atomically with the state change and Envers revision. Three work-order-specific HTTP error codes added to ErrorEnvelope.Code and wired into GlobalExceptionHandler. Legacy applyEvent() preserved unchanged for backward compatibility with existing lifecycle integration tests.

## WO-148: User Story: WO-148 - Parts catalog, stock locations, and balance foundation
- **Status:** completed
- **Commit:** `6649872`
- **Files:** 16 (+1292/-23)
- **Duration:** 958ss
- **Approach:** N/A

## WO-184: User Story: WO-184 - Per-account appearance preference persistence and flash-free restore
- **Status:** completed
- **Commit:** `4c150ad`
- **Files:** 15 (+1057/-0)
- **Duration:** 788ss
- **Approach:** Expand-only V15 migration adds nullable appearance_preference VARCHAR(10) with a CHECK(IN ('LIGHT','DARK','SYSTEM')) to app_user and mirrors the column in app_user_aud for Envers. AppUser gains an @Enumerated(STRING) AppearancePreference field (Envers-audited, INTERNAL classification). UserPreferencesService derives the subject exclusively from AccessScopeResolver (no client-supplied ID, IDOR-closed), loads the user by ID, and reads/writes the preference within @Transactional boundaries so Envers revision and domain update are atomic. UserPreferencesController exposes GET/PUT /api/v1/users/me/preferences with @Valid Bean Validation; invalid enum values produce 400 via GlobalExceptionHandler (HttpMessageNotReadableException); unknown properties are rejected by the global fail-on-unknown-properties=true Jackson config. On the web side: appearanceMirror.js reads/writes/clears the fs-appearance localStorage key with tampered-value fallback; resolveAppearance.js maps LIGHT→light, DARK→dark, SYSTEM→OS, null→light; AppearanceProvider.jsx mutates only data-appearance on <html> (no stylesheet swap, <100ms budget); preferences.js provides the API client; appearance.test.js covers all unit cases; index.html already has a CSP-compatible pre-paint bootstrap using the same key.

## WO-185: User Story: WO-185 - Application shell with role-derived navigation and code splitting
- **Status:** completed
- **Commit:** `f1e4a02`
- **Files:** 40 (+2311/-8)
- **Duration:** 1228ss
- **Approach:** Single composition root (AppProviders.jsx) mounts QueryClientProvider → AuthContext → AppearanceProvider → DensityProvider → ToastProvider → AppRouter in documented order. AppShell.jsx frames the CSS Grid layout (240px sidebar, 56px topbar, max-width 1440px) using a CSS module and WO-087 primitives. Sidebar collapses to 64px icon rail with localStorage persistence (key: fs-sidebar-collapsed) and switches to off-canvas drawer below 768px. Navigation is derived from the roles claim via filterNavForRoles() — documented as a usability affordance only (A01, BR-19); server 403 is the security boundary, mapped by RouteErrorBoundary to PermissionDeniedState. Four surface route groups (dispatch, field, operations, portal) are lazily loaded via React.lazy + Suspense; vite.config.js manualChunks ensures charting and dispatch code never land in the field surface entry. Service worker (fieldServiceWorker.js) is registered only for the field surface, caches assigned-jobs GET responses with x-sw-stale header for offline degraded indicator — tokens and mutation responses are never cached. Offline mutation guard via useNetworkStatus.guardMutation() blocks writes when offline; no queueing or silent retry (AC-8). ErrorBoundary (class component) wraps the outlet; renders ErrorState + traceId or PermissionDeniedState for 403, never a stack trace (A10). All styling uses CSS modules with var(--token-*) references — zero bespoke literals in JSX inline styles.

## WO-187: User Story: WO-187 - Persona density variants and dual-appearance accessibility gate
- **Status:** completed
- **Commit:** `55a46aa`
- **Files:** 30 (+2739/-3)
- **Duration:** 1271ss
- **Approach:** Four persona density variants (technician-first per BR-35) added to src/density/personaDensity.js as token-only overrides with pixel helpers for test matrix assertions. Technician: 44px touch target, single column, bottom-anchored primary action, system font, 7:1 body contrast in light. Dispatcher: 32px compact rows, drawer detail, keyboard-first. Operations: 56px KPI cards, chart-forward, 4 desktop columns. Customer: 72px spacious rows, plain language, 2 desktop columns. Contrast helper (contrast.js) parses both token value sets, resolves foreground/background pairings from contrastPairings.json, computes WCAG ratios, and asserts 4.5:1 generally / 7:1 for technician body text in light. greyscaleAssertion.js checks DOM elements for text label + icon/shape without CSS colour. ChartTableEquivalent.jsx renders accessible table with same values as chart series; ChartTableEquivalent.test.jsx asserts value parity, empty state, scope attributes. Seven-entry CVD-safe series palette (seriesPalette.js) uses distinct token hues and shapes. Playwright tests cover axe-core both appearances, keyboard traversal, greyscale filter audit, reduced-motion emulation, INP/CLS performance and appearance-switch timing. audit-design-system.mjs counts token-referencing vs total visual declarations and fails below 95% adoption, above 5% bespoke, or on any hard-coded literal. stylelintrc.json adds outline:none/0 to the disallowed list. package.json adds axe-core, @axe-core/playwright, @playwright/test plus test:a11y, test:performance and audit:design-system scripts wired into build:ci/build:node.

## WO-201: User Story: WO-201 - Deterministic Test Fixture And Seed Data Library
- **Status:** completed
- **Commit:** `01cdb2b`
- **Files:** 16 (+2424/-0)
- **Duration:** 1131ss
- **Approach:** Created a deterministic test fixture library in the new `com.fieldservice.fixtures` package with seven builder classes. DeterministicIds provides a fixed Clock (2025-01-15T10:00:00Z) and a monotonic UUIDv7 generator seeded from that constant instant, resettable between scenarios for reproducibility. UserFixtures builds AppUser + RoleAssignment for all five roles (ADMIN, DISPATCHER, TECHNICIAN, MANAGER, CUSTOMER); BCrypt cost-12 hashes are computed once per JVM via a lazy volatile initializer using BCryptPasswordEncoder directly (no Spring context required) and stored in {bcrypt}$2a$12$... format matching DelegatingPasswordEncoder. TechnicianFixtures covers active, expiring-soon (7d), expired (−1d from EPOCH boundary), inactive, and revoked certification templates. WorkOrderFixtures produces all eight lifecycle states with lifecycle consistency: COMPLETED description includes labour time, CLOSED includes parts-consumption reconciliation; SLA deadlines derived from EPOCH + priority offsets. CustomerFixtures, InventoryFixtures cover all remaining aggregates. ScenarioFixtures composes the dispatch-ready scenario (NEW HIGH WO + 4 techs + stocked/empty warehouse) and the all-states scenario. seed-core.sql seeds SLA policies, 10 parts, and a reference customer/site/asset set using ON CONFLICT DO NOTHING (idempotent). FixtureHygieneTest scans fixture sources and seed SQL for non-reserved email domains, non-reserved phone numbers, and BCrypt placeholder strings. FixtureGraphIT is an integration test extending AbstractIntegrationTest that persists the full fixture graph through Hibernate and asserts FK/CHECK/NOT NULL constraints hold.

## WO-005: User Story: WO-005 - Worker outbox drain with SKIP LOCKED and idempotent dispatch
- **Status:** completed
- **Commit:** `5d2bf36`
- **Files:** 14 (+1119/-0)
- **Duration:** 1329ss
- **Approach:** Implemented the outbox drain pipeline using a JDBC-first approach to keep FOR UPDATE SKIP LOCKED within proper transactions. OutboxDrainService uses a per-event REQUIRES_NEW TransactionTemplate (not @Transactional, to avoid self-invocation issues) that atomically claims one row, dispatches to the matching handler, and marks success or records failure within the same TX. Handlers implement the EventHandler interface from the platform module and use IdempotencyGuard.claimEvent() as the first operation before side effects — a unique violation against the processed_event table is an already-processed no-op. Jittered exponential backoff is computed by BackoffCalculator and stored as next_attempt_at; after maxAttempts failures the event is dead-lettered (dead_lettered_at set) and permanently excluded from the claim predicate. DatabaseSchedulingLock uses INSERT … ON CONFLICT DO UPDATE … WHERE with database now() for clock-skew-proof leader election. OutboxPoller is the thin @Profile('worker') @Scheduled wrapper; tests call OutboxDrainService directly without needing the worker profile.

## WO-110: User Story: WO-110 - Rotate refresh tokens with family reuse detection
- **Status:** completed
- **Commit:** `227f7c0`
- **Files:** 13 (+1537/-4)
- **Duration:** 1040ss
- **Approach:** Refresh-token rotation implemented via a single atomic conditional UPDATE (consumed_at WHERE NULL) to prevent read-then-write races under concurrency. Reuse detection revokes the entire family and publishes a RefreshTokenReuseDetected event through the existing transactional outbox; subsequent reuse on an already-revoked family only increments a Micrometer counter (SIEM deduplication). The V17 migration adds absolute_expires_at (expand-only, backward-compatible default) to refresh_token_family. The AuthController POST /refresh endpoint reads exclusively from the HttpOnly refreshToken cookie, validates the 43-char base64url handle before any DB lookup, and collapses all failure modes to the same 401 REAUTHENTICATION_REQUIRED shape. Plaintext handles never reach the database.

## WO-113: User Story: WO-113 - Enforce mandatory row-scope AccessScope query predicates
- **Status:** completed
- **Commit:** `91d1db7`
- **Files:** 12 (+1014/-16)
- **Duration:** 1272ss
- **Approach:** The row-scope enforcement framework (AccessScope, AccessScopeResolver, AccessScopePredicateFactory, ScopedQueryExecutor, ScopedRepository, ScopedEntity) was already in place from prior WOs. This WO completed the framework by: (1) implementing the single 403-vs-404 translation point (ScopeDenialTranslator — CUSTOMER cross-account → 404, all other scope denials → 403) with structured audit log and Micrometer counter; (2) wiring the translator into GlobalExceptionHandler replacing the fixed 403; (3) adding Micrometer counter to RestAccessDeniedHandler for filter-chain denials; (4) creating GET /api/v1/work-orders/{id} as the representative read endpoint used by the HTTP probe matrix; (5) adding the ArchUnit scoped-repository rule with a non-compliant fixture proving the rule fires; (6) extending TestTokenMinter with extra-claims support for HTTP-level technician/customer token minting; (7) writing SQL inspection, HTTP probe matrix, and unit tests.

## WO-114: User Story: WO-114 - Author and enforce RBAC method-security role matrix
- **Status:** completed
- **Commit:** `c696016`
- **Files:** 8 (+609/-0)
- **Duration:** 882ss
- **Approach:** Authored the RBAC matrix as a dual-form artefact: human-readable docs/security/rbac-matrix.md (Phase 1 security gate evidence with reviewer/date) and machine-readable rbac-matrix.yml (7 operations × 5 roles driving parameterized tests). Added @PreAuthorize to WorkOrderTransitionService interface methods so background callers cannot bypass service-layer checks. ArchUnit MethodSecurityTest enforces annotation coverage on all public non-static @Service methods in business packages; a deliberately non-compliant UnannotatedServiceMethod fixture proves the rule fires. Semgrep rule in .semgrep/authorization-rules.yml mirrors the ArchUnit requirement for pipeline enforcement outside the JVM. RbacMatrixTest is a 35-cell parameterized integration test (MockMvc + jwt() post processor against Testcontainers PostgreSQL) that reads rbac-matrix.yml at runtime, asserts 403 for denied combinations and non-403 for allowed ones, and includes a completeness check that every matrix entry references a real class and method.

## WO-115: User Story: WO-115 - Issue single-use IP-bound SSE stream tickets
- **Status:** completed
- **Commit:** `079af6e`
- **Files:** 12 (+1310/-1)
- **Duration:** 734ss
- **Approach:** Implemented single-use IP-bound SSE stream tickets as a 3-layer design: StreamTicketStore interface (Redis via Lua atomic GET+DEL, InMemory test fallback), StreamTicketService (256-bit SecureRandom issuance, atomic redemption with IP/jti/account validation, Micrometer counters), and StreamTicketAuthenticationFilter (OncePerRequestFilter scoped to /api/v1/streams/**, rejects ticket param on non-stream paths). POST /api/v1/auth/stream-ticket added to AuthController with @PreAuthorize(isAuthenticated()) and 503 fail-closed on store unavailability. SecurityFilterChainConfig registers the filter before UsernamePasswordAuthenticationFilter. The ticket value is never persisted — only its SHA-256 hex digest appears in Redis as the key.

## WO-125: User Story: WO-125 - Business precondition guards for lifecycle transitions
- **Status:** completed
- **Commit:** `566149e`
- **Files:** 30 (+1568/-18)
- **Duration:** 1107ss
- **Approach:** N/A

## WO-126: User Story: WO-126 - Controlled hold reason vocabulary and resume handling
- **Status:** completed
- **Commit:** `aa9d575`
- **Files:** 19 (+1035/-7)
- **Duration:** 1041ss
- **Approach:** Modelled holds as intervals in an Envers-audited work_order_hold table backed by a hold_reason controlled vocabulary. A Redis short-TTL cache (5 min, DB fallback on miss) serves the vocabulary via HoldReasonService. Vocabulary validation throws InvalidHoldReasonCodeException (HTTP 400 field error) before guard evaluation. HOLD transition inserts an open hold record inside the existing write transaction; RESUME and any transition away from ON_HOLD closes it and accumulates ceiling-rounded minutes on work_order.cumulative_hold_minutes. A partial unique index on work_order_hold(work_order_id) WHERE ended_at IS NULL enforces at most one open hold. GET /api/v1/work-orders/hold-reasons returns active reasons in the standard PagedResponse envelope.

## WO-127: User Story: WO-127 - Paginated work order search with row-scoped access
- **Status:** completed
- **Commit:** `b4777ec`
- **Files:** 10 (+1186/-0)
- **Duration:** 1017ss
- **Approach:** Implemented a paginated, row-scoped work order search endpoint at GET /api/v1/work-orders. WorkOrderSearchCriteria captures all optional filters; WorkOrderSearchService composes them into a JPA Specification via buildFilterSpec(), delegates to SpecificationPageService which ANDs the AccessScope predicate (via ScopedQueryExecutor) so out-of-scope rows are never loaded. SortAllowList gates all sort fields and rejects unknowns with 400. WorkOrderBoardRow is a projection DTO; entities are never returned. Page size is clamped to 50 in PageQuery. ETag/If-None-Match conditional GET is handled in the controller. Expand-only migration V20 adds composite and partial indexes. A MethodArgumentTypeMismatchException handler was added to GlobalExceptionHandler to return 400 with field-level errors for invalid enum values in request params.

## WO-149: User Story: WO-149 - Atomic parts consumption enforcing non-negative stock
- **Status:** completed
- **Commit:** `a357ccf`
- **Files:** 21 (+1744/-1)
- **Duration:** 1031ss
- **Approach:** N/A

## WO-186: User Story: WO-186 - Shared data layer with error mapping, refresh, and SSE ticket
- **Status:** completed
- **Commit:** `5b4efad`
- **Files:** 24 (+3073/-27)
- **Duration:** 969ss
- **Approach:** Built the single TanStack Query 5.x data layer in src/api/. Token store uses module-scope variable only (no web storage). Single-flight refresh in http.js uses a module-scoped Promise guard so concurrent 401s produce exactly one refresh call. All 4xx statuses have retryable=false (A10 constraint). ETag/304 conditional polling in useConditionalQuery.js returns undefined on 304 so TanStack Query preserves cached data with no re-render. SSE client fetches a single-use stream ticket via POST (bearer token in header) and passes it as a query parameter to EventSource — access token never appears in a URL. Jittered exponential backoff governs reconnects with fresh ticket each time. eventKeyMap.js maps SSE event types to query key prefix arrays for targeted invalidation. generate-api-client.mjs produces JSDoc typedefs and endpoint accessors from the OpenAPI spec and supports --check for CI drift detection.

## WO-202: User Story: WO-202 - Testcontainers Postgres Integration Test Harness
- **Status:** completed
- **Commit:** `1261687`
- **Files:** 12 (+1530/-24)
- **Duration:** 978ss
- **Approach:** Bottom-up harness construction: (1) PostgresContainerSupport provides a static singleton PostgreSQL 16 container with withReuse(REUSE_ENABLED) guarded by CI env var and DynamicPropertySource datasource registration. (2) support.AbstractIntegrationTest extends it with @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles(test). (3) security.AbstractIntegrationTest migrated to extend PostgresContainerSupport — all 30+ existing tests continue without change. (4) DatabaseCleaner issues TRUNCATE ... RESTART IDENTITY CASCADE over ~30 application tables in FK dependency order, excluding role/sla_policy/flyway_schema_history. (5) AuditAssertions queries *_aud + revinfo via JDBC returning RevisionRecord(revNumber, revType, actorUserId). (6) OutboxAssertions queries outbox_event by aggregate_id returning OutboxEventRecord. (7) RedisContainerSupport is an opt-in extension with Redis 7 container registered via DynamicPropertySource. (8) SchemaShapeTest asserts revinfo, revinfo_seq and all 9 *_aud tables exist after Flyway migrations. (9) IsolationStrategyTest proves both strategies plus DatabaseCleaner unit tests with no container. (10) WorkOrderLifecycleE2ETest boots full context, performs authenticated transitions via HTTP, asserts persisted rows + Envers revisions + outbox events. (11) JaCoCo configured in app/pom.xml with prepare-agent/prepare-agent-integration, merge at post-integration-test, report + 80% line check at verify. (12) TESTING.md at repo root covers all documentation requirements.

## WO-111: User Story: WO-111 - Revoke session on logout with jti denylist
- **Status:** completed
- **Commit:** `2ce4e73`
- **Files:** 6 (+937/-0)
- **Duration:** 918ss
- **Approach:** Implemented POST /api/v1/auth/logout with a split-transaction ordering guarantee: family revocation commits first via TransactionTemplate, then the jti denylist write occurs outside that transaction so Redis failure cannot roll back the committed revocation. The controller extracts jti/exp from the raw Authorization header using Nimbus JWTParser without signature validation, enabling correct handling of expired tokens. All not-found and already-revoked paths are silent no-ops for idempotency. The LogoutService is @Service without class-level @Transactional, uses sealed LogoutResult variants, and emits a UserLoggedOut outbox event inside the transaction. The integration test imports SecurityFilterChainTestConfig (real denylist-aware decoder) and uses TestTokenMinter for all access tokens to avoid signing key mismatch with the login endpoint.

## WO-116: User Story: WO-116 - Build sign-in screen with in-memory token custody
- **Status:** completed
- **Commit:** `df03d43`
- **Files:** 13 (+1590/-8)
- **Duration:** 964ss
- **Approach:** Implemented the sign-in screen as a split-layout page (BrandPanel + AuthCard) in src/features/auth/ (not src/surfaces/ so coverage thresholds apply). The useSignIn hook uses TanStack Query useMutation with retry:false, direct fetch with credentials:include for the login endpoint, normalises all status codes into a typed LoginError including network failures (status:0), boundary-validates the response before the token reaches tokenStore, and exposes attemptBootRefresh() for mount-time silent re-authentication from the HttpOnly cookie. The SignInPage mounts with bootRefreshing:true, attempts refresh, then either navigates away (success) or shows the form. Error routing: 400 with fieldErrors → per-field aria-describedby errors; all other failures → GenericErrorAlert with aria-live=assertive. Double-submit prevented by disabling the button and early-returning in handleSubmit while isPending. SSO section behind VITE_SSO_ENABLED feature flag. ESLint restricted-properties bans localStorage/sessionStorage access in src/features/auth/** and src/surfaces/auth/**.

## WO-117: User Story: WO-117 - Catalog module: customer, site and asset reference data
- **Status:** completed
- **Commit:** `eb1a30f`
- **Files:** 26 (+2695/-37)
- **Duration:** 1138ss
- **Approach:** Layered the catalog module on top of the existing Customer/Site/Asset domain entities rather than creating parallel entities. Enhanced the existing entities with new V22 catalog fields and @Audited annotations. The catalog module follows the inventory module pattern: api/ package (CatalogQueryPort + projection records), application/ package (CatalogService with @PreAuthorize, hierarchy guards, cascade deactivation, and outbox), and web/ package (three REST controllers). All reads route through ScopedQueryExecutor for mandatory row-scope enforcement. SortAllowList per entity prevents sort injection. Outbox events (CustomerChanged, SiteChanged, AssetChanged) are published atomically with the domain write via DomainEventPublisher(MANDATORY).

## WO-142: User Story: WO-142 - Runtime-configurable SLA policy and deadline derivation
- **Status:** completed
- **Commit:** `1b36804`
- **Files:** 31 (+1164/-5)
- **Duration:** 1084ss
- **Approach:** N/A

## WO-150: User Story: WO-150 - Append-only stock ledger and reconciliation integrity check
- **Status:** completed
- **Commit:** `fe15fed`
- **Files:** 15 (+1260/-9)
- **Duration:** 1071ss
- **Approach:** Expand-only V26 migration adds 7 new columns to stock_ledger plus REVOKE UPDATE/DELETE for the fieldservice DB role, and adds no_parts_required to work_order. StockLedger entity gains those fields with getters only (no setters for immutable fields). StockMovementServiceImpl.buildLedgerEntry is overloaded to populate all enrichment fields; transferStock generates a shared correlationId for paired TRANSFER_OUT/TRANSFER_IN entries and calls InventoryMetrics.incrementLedgerEntriesWritten() per entry. StockReconciliationService sums ledger deltas via JPQL projection and compares to stock_balance via JdbcTemplate, logging ERROR on discrepancy without auto-correction. StockReconciliationJob runs on the worker profile behind a DatabaseSchedulingLock. MovementQueryService enforces CUSTOMER 403 and serves the paginated/filterable movement history via ScopedQueryExecutor. InventoryMovementsController exposes GET /api/v1/inventory/movements. ArchUnit rule in InventoryBoundaryTest prevents any class from calling delete* on StockLedgerRepository. StockLedgerMutationIT verifies DB permission denial for UPDATE/DELETE (skips under superuser). StockReconciliationServiceTest covers all reconcile() and completeness ratio paths with a fixed Clock and no Spring context. V112 fixtures provide 30 days of synthetic ledger history, a mismatched balance row, and three completed WOs for completeness metric testing.

## WO-161: User Story: WO-161 - Analytics read-model substrate with debounced outbox consumer
- **Status:** completed
- **Commit:** `7ef8ae2`
- **Files:** 22 (+2121/-1)
- **Duration:** 872ss
- **Approach:** Built the analytics read-model substrate bottom-up: (1) V27 expand-only Flyway migration creates kpi_projection and analytics_processed_event tables with unique metric/segment/window key, data_as_of index, maturity CHECK, and conditional GRANT for the fieldservice role. (2) Public interface: KpiProjection immutable record (value, numerator, denominator, data_as_of, projectionVersion, degraded, stalenessSeconds) and KpiProjectionQuery port. (3) KpiProjectionEntity (JPA) + KpiProjectionRepository (plain JpaRepository, non-scoped, allow-listed in ScopedRepositoryArchTest). (4) MetricDebounceRegistry: ConcurrentHashMap of metric → eligibleAt Instant, putIfAbsent for earliest-wins coalescing, drainExpired() removes and returns keys whose 15s window has passed; Clock injected. (5) KpiOutboxConsumer: maps WorkOrderCreated/WorkOrderStateChanged/PartsConsumed event types to metric keys, inserts into analytics_processed_event for idempotency (DataIntegrityViolationException = already processed), calls debounceRegistry.markDirty(). (6) KpiEventHandlers: three inner @Component classes implementing EventHandler, one per event type, delegating to KpiOutboxConsumer. (7) KpiProjectionService: read path checks Redis cache by version-keyed key → DB fallback → repopulate cache → DegradationPolicy on cache failure; write path runs aggregation via replicaJdbcTemplate → upserts projection row → bumps projectionVersion → evicts/repopulates cache. (8) KpiAggregationQueries: three seed metrics (backlog_count, completion_rate_7d, sla_compliance_7d) via named parameterized JDBC queries against replicaJdbcTemplate only. (9) AnalyticsDataSourceConfig: creates replicaDataSource bean (HikariCP, reads from app.analytics.replica-url or falls back to primary with WARN log) and replicaJdbcTemplate bean. (10) AnalyticsRedisCache: @ConditionalOnBean(StringRedisTemplate) with version-keyed keys, 30s TTL, evict-then-repopulate pattern; cache miss on version mismatch is structural. (11) DegradationPolicy: translates replica/cache exceptions to degraded KpiProjection carrying last known value. (12) AnalyticsMetrics: staleness gauge (AtomicLong), refresh lag timer, cache hit/miss counters all tagged by metric key. (13) KpiProjectionRefreshJob: @Profile(worker) @EnableScheduling, 1s fixedDelay flush loop, drains debounceRegistry, acquires distributed lock per metric via SchedulingLock.runIfLeader(). (14) AnalyticsBoundaryTest: 3 ArchUnit rules — no outside access to analytics.internal, analytics may not depend on domain entity packages, analytics may not depend on aigateway. (15) Unit tests: MetricDebounceRegistryTest (7 tests, fixed Clock, burst coalescing AC-4), KpiIdempotencyTest (4 tests, Mockito, duplicate event AC-3). (16) IT: KpiAnalyticsIT in analytics.internal package (PostgreSQL + Redis Testcontainers).

## WO-188: User Story: WO-188 - Data classification registry with per-entity tier metadata
- **Status:** completed
- **Commit:** `9d25d6f`
- **Files:** 28 (+1509/-4)
- **Duration:** 1026ss
- **Approach:** N/A

## WO-195: User Story: WO-195 - Resilient notification delivery port with degraded in-app fallback
- **Status:** completed
- **Commit:** `1f56c89`
- **Files:** 29 (+1753/-0)
- **Duration:** 1058ss
- **Approach:** N/A

## WO-200: User Story: WO-200 - ArchUnit Fitness Tests For Module And Security Boundaries
- **Status:** completed
- **Commit:** `f40c32d`
- **Files:** 12 (+704/-0)
- **Duration:** 709ss
- **Approach:** Extended the existing ArchUnit fitness suite with four new rule classes (LayeredArchitectureTest, ModuleBoundaryTest, InjectionAndCryptoRulesTest, DtoBoundaryTest), five new fixture classes covering each violation type, a configured TextFileBasedViolationStore with a pre-populated freeze entry for WorkOrderController's pre-existing layering violation, and a comprehensive ArchUnit rules section in TESTING.md. All rules use ArchUnit 1.3.0 (already in pom.xml) and the @AnalyzeClasses pattern established by prior WOs.

## WO-203: User Story: WO-203 - Role And Row-Scope Access Control Test Matrix
- **Status:** completed
- **Commit:** `2f3e215`
- **Files:** 4 (+1161/-0)
- **Duration:** 923ss
- **Approach:** Created a declarative Java-code matrix (AccessControlMatrix) enumerating all 30+ protected endpoints with expected HTTP status per role (DISPATCHER, TECHNICIAN, MANAGER, CUSTOMER, ADMIN, UNAUTHENTICATED). The matrix is driven by a parameterized integration test (AccessControlMatrixTest) that mints real RS256 JWTs via TestTokenMinter/TestRsaKeyPair and routes them through the production SecurityFilterChainTestConfig JwtDecoder. An EndpointCoverageTest loads the full Spring context via RequestMappingHandlerMapping and fails the build if any endpoint is absent from the matrix. Token-shape negatives, row-scope SQL proofs, and service-layer method-security proofs are already covered by SecurityFilterChainIntegrationTest, ScopedQuerySqlInspectionTest, and MethodSecurityTest respectively; those tests were not duplicated.

## WO-204: User Story: WO-204 - P0 API Contract And Envelope Conformance Suite
- **Status:** completed
- **Commit:** `a0de9b6`
- **Files:** 7 (+1359/-0)
- **Duration:** 903ss
- **Approach:** Created shared ApiAssertions helpers (assertEnvelope, assertErrorShape, assertPageMeta, assertEmptyEnvelope, assertLastPage, assertNoInternalLeak) and one contract test class per P0 endpoint group. All tests extend AbstractIntegrationTest (Testcontainers PostgreSQL + stub JWT decoder on 'test' profile), with TransitionContractTest and PartsConsumptionContractTest additionally annotated @ActiveProfiles('api') to activate IdempotencyKeyFilter. Tests focus on contract-level assertions (envelope shape, error shape, pagination invariants, idempotency proof) rather than duplicating functional coverage in existing WorkOrderTransitionControllerIT/WorkOrderPartsIT. OpenApiConformanceTest validates live responses against required fields declared in OpenAPI component schemas, and asserts no undocumented top-level fields in collection responses.

## WO-118: User Story: WO-118 - Workforce module: technician profiles, skills and availability
- **Status:** completed
- **Commit:** `9d66b0e`
- **Files:** 34 (+1182/-0)
- **Duration:** 1064ss
- **Approach:** N/A

## WO-128: User Story: WO-128 - Work order creation with automatic deadline derivation
- **Status:** completed
- **Commit:** `0bf4c6b`
- **Files:** 16 (+827/-29)
- **Duration:** 966ss
- **Approach:** N/A

## WO-153: User Story: WO-153 - Stock position screens with low-stock and staleness states
- **Status:** completed
- **Commit:** `30fd94d`
- **Files:** 19 (+2593/-2)
- **Duration:** 823ss
- **Approach:** Built the inventory stock-position and low-stock screens plus a technician parts-logging panel. All shared primitives (idempotency key management, freshness evaluation) live in src/lib/. A typed API client in src/api/inventory.js wraps all inventory endpoints. Components use TanStack Query useQuery with refetchInterval=30s and refetchIntervalInBackground=false (hidden-tab pause). Degraded state triggers when evaluateFreshness detects the as-of timestamp exceeds the 60-second BR-15 budget. The AlertIndicator on LowStockPage carries text+icon+shape (never colour-only) per AC-3/BR-32/BR-34. PartsLoggingPanel implements debounced search, multi-line staging, idempotency-key lifecycle (same key on retry, new key on fresh submission), 422 per-line insufficient-stock detail with hold action, and network-failure not-connected state. All interactive targets are ≥44px with no horizontal scroll at 360px. Role-based hiding uses explicit USABILITY-ONLY comment. Surfaces wired: dispatch /inventory and /inventory/low-stock; field /jobs/:workOrderId/parts.

## WO-164: User Story: WO-164 - First-time fix rate with matured cohort linkage
- **Status:** completed
- **Commit:** `7883dc1`
- **Files:** 15 (+1598/-2)
- **Duration:** 1315ss
- **Approach:** Implemented first-time fix rate quality metrics by adding fault_code/fault_category columns to work_order, a new analytics_closure_projection table for cohort tracking, and a repeat_visit_link table for repeat-visit detection. Core domain components (FaultKeyDeriver, RepeatVisitLinker, CohortMaturityResolver) are placed in analytics.internal.quality subpackage as public classes. Components requiring access to package-private MetricDebounceRegistry (FirstTimeFixCalculator, QualityMetricKeys, QualityKpiOutboxConsumer, MaturationSweepJob) are in analytics.internal. The OutboxDrainService single-handler constraint is respected by routing WorkOrderStateChanged events through the existing KpiEventHandlers.WorkOrderStateChangedHandler which also dispatches to QualityKpiOutboxConsumer. KpiProjectionService is extended with FirstTimeFixCalculator injection and quality metric cases in runAggregation(). The FTF_PROVISIONAL metric is stored with segment_key=PROVISIONAL per BR-30.

## WO-165: User Story: WO-165 - Backlog and workload balance guardrail projections
- **Status:** completed
- **Commit:** `0712c8c`
- **Files:** 12 (+1082/-2)
- **Duration:** 790ss
- **Approach:** Extended the analytics KPI substrate (WO-161) with three new metrics: backlog.open.count (total open work orders segmented by state/priority/hold_reason), backlog.on_hold.count, and workforce.workload_balance.cv (population coefficient of variation of assigned hours per active technician). Added a kpi_trend_point table for immutable daily backlog snapshots. Open states are derived at class-init time via EnumSet.complementOf(TERMINAL_STATES) so any future lifecycle state addition automatically adjusts counts. The CV computation uses population stddev (divides by N, not N-1) as the entire active team is measured, not a sample. NOT_MEANINGFUL is returned as a typed enum reason (not null or magic number) when N < 3 or mean = 0. Trend points use ON CONFLICT DO NOTHING so the first write of a given calendar day is immutable.

## WO-169: User Story: WO-169 - Customer account linkage and row-scope access predicate
- **Status:** completed
- **Commit:** `35b3c6f`
- **Files:** 17 (+1238/-2)
- **Duration:** 678ss
- **Approach:** Implemented the portal linkage foundation for WO-169. Created V34 Flyway migration for portal_account_user (user→account binding with status check, UUIDv7 PK, UNIQUE(user_id)) and portal_invitation (single-use token, AES-256-GCM encrypted contact fields) with Envers AUD mirrors. The PortalAccountUser and PortalInvitation entities extend BaseEntity and are @Audited; contact fields carry @NotAudited so ciphertext is excluded from audit tables (ciphertext has no forensic value). CustomerAccessScope is a @RequestScope @Component that looks up portal_account_user by userId, fails closed on missing or non-ACTIVE rows (throws ScopeUnavailableException), caches the resolved account_id per-request, and exposes a JPA Specification that INNER-JOINs work_order→site and constrains site.customer_id = accountId. PortalExceptionAdvice encodes the single disclosure rule: ScopeUnavailableException and NotFoundException both map to HTTP 404 with identical PORTAL_RESOURCE_NOT_FOUND code and message; AccessDeniedException maps to 403. InvitationService generates 256-bit SecureRandom tokens (base64url), stores only their SHA-256 hex hash, enforces expiry and single-use guards, and requires ADMIN or DISPATCHER role for issuance. A new PortalArchTest enforces that future portal query repositories extend ScopedRepository; infrastructure repos (PortalAccountUserRepository, PortalInvitationRepository) are allow-listed. MethodSecurityTest extended to cover portal service packages.

## WO-189: User Story: WO-189 - Configurable retention schedule with automated purge sweep
- **Status:** completed
- **Commit:** `104ad96`
- **Files:** 21 (+1652/-1)
- **Duration:** 1712ss
- **Approach:** Implemented a runtime-configurable GDPR/CCPA retention schedule with automated purge sweep in the existing privacy Maven module. Added Flyway V35 migration creating retention_policy and purge_run tables with Hibernate Envers AUD mirrors, BRIN indexes, and 5 seed rows (all ratified=false pending DPO ratification). Defined the RetentionTarget SPI interface in the public API package so domain modules can register disposal implementations. The service layer (RetentionPolicyServiceImpl) is package-private, exposes a public RetentionPolicyAdminPort, enforces a 1-year audit floor for AUDIT_RECORDS via BusinessGuardException (422), uses optimistic locking via @Version for conflict detection (409), and validates period units/disposal methods. PurgeSweepJob runs on the worker profile with a master kill-switch (privacy.purge.execution-enabled=false by default), acquires a distributed lease via SchedulingLock, processes only ratified+enabled+non-held policies in bounded batches, respects a per-run time budget, and writes an immutable PurgeRun audit log entry per category. ZonedDateTime is used throughout for DST-safe cutoff arithmetic. All config is externalized via @ConfigurationProperties with env-var overrides.

## WO-190: User Story: WO-190 - Data subject access and portability export workflow
- **Status:** completed
- **Commit:** `53deee5`
- **Files:** 37 (+2277/-2)
- **Duration:** 1086ss
- **Approach:** Built a persisted DSAR queue on top of the existing privacy Maven module using the declarative transition-table pattern established by the work order lifecycle. State machine (RECEIVED→IDENTITY_PENDING→VERIFIED→IN_PROGRESS→FULFILLED / REJECTED / WITHDRAWN) is encoded in an immutable DsarTransitionTable with two guards: VerificationMethodRequiredGuard and ArtifactExistsGuard. Export assembly runs as a @Scheduled worker job on the 'worker' Spring profile, guarded by SchedulingLock, aggregating SubjectDataProvider beans from all modules. Storage abstraction (ExportStoragePort / InMemoryExportStorage via @ConditionalOnMissingBean) allows production swap-in of real object storage without code change. Privacy module only reads personal data through each module's SubjectDataProvider, never touching foreign tables. WO-189 carry-over fix: removed invalid Spring Data derived query countByOutcomeAndDueAtAfterSubmittedAt() from DsarRequestRepository.
