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
