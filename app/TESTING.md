# Testing Guide — field-service-api

## Fixture library

Every test that needs realistic domain objects should build them from the
fixture library in `com.fieldservice.fixtures` rather than writing inline setup.

### Quick start

```java
// Reset the ID counter at the top of each scenario
DeterministicIds.reset();

// One-line: a dispatch-ready scenario
ScenarioFixtures.DispatchReadyScenario s = ScenarioFixtures.dispatchReady();

// Single aggregate
AppUser admin = UserFixtures.admin().build();
Technician tech = TechnicianFixtures.defaults(admin.getId()).build();
WorkOrderFixtures.WorkOrderGraph g = WorkOrderFixtures.completed(site, tech.getId());
```

### Package overview

| Class | Builds |
|---|---|
| `DeterministicIds` | Fixed `Clock` + reproducible UUID supplier |
| `UserFixtures` | `AppUser` (5 roles) + `RoleAssignment` |
| `TechnicianFixtures` | `Technician` + `TechnicianCertification` |
| `WorkOrderFixtures` | `WorkOrder` in all 8 lifecycle states + `Assignment` |
| `InventoryFixtures` | `Part`, `StockLocation`, `StockBalance`, `SlaPolicy` |
| `ScenarioFixtures` | Named multi-aggregate scenarios |

### Determinism guarantee

All fixture IDs and timestamps are derived from a fixed reference instant
(`2025-01-15T09:00:00Z`) and a monotonic counter.  Two identical builds of the
same scenario produce bit-identical UUIDs when the counter is reset between them.

```java
DeterministicIds.reset();
UUID first = DeterministicIds.next();
DeterministicIds.reset();
UUID second = DeterministicIds.next();
assertThat(first).isEqualTo(second); // always passes
```

**Call `DeterministicIds.reset()` at the start of every test or scenario** to
restart the counter from zero.  Without a reset, counter state leaks between
tests and IDs become non-reproducible.

### Fixed Clock

Use `DeterministicIds.CLOCK` wherever a `java.time.Clock` is injectable:

```java
new SomeService(DeterministicIds.CLOCK)
```

The fixed instant is `DeterministicIds.FIXED_INSTANT = Instant.parse("2025-01-15T09:00:00Z")`.

### Certification boundary cases

| Factory method | `expiresAt` relative to `FIXED_INSTANT` |
|---|---|
| `validCertification(techId)` | +30 days — valid |
| `nonExpiringCertification(techId)` | `null` — never expires |
| `expiredCertification(techId)` | −1 day — expired |
| `boundaryExpiredCertification(techId)` | = FIXED_INSTANT — boundary, treated as expired |

### Work-order lifecycle consistency

| Factory method | State | Has `Assignment` | `releasedAt` set |
|---|---|---|---|
| `newOrder(site)` | NEW | no | — |
| `assigned(site, techId)` | ASSIGNED | yes | no |
| `enRoute(site, techId)` | EN_ROUTE | yes | no |
| `inProgress(site, techId)` | IN_PROGRESS | yes | no |
| `onHold(site, techId)` | ON_HOLD | yes | no |
| `completed(site, techId)` | COMPLETED | yes | **yes** |
| `closed(site, techId)` | CLOSED | yes | **yes** |
| `cancelled(site)` | CANCELLED | no | — |

COMPLETED and CLOSED orders carry an `Assignment` with a non-null `releasedAt`
representing logged labour time (90 minutes from `assignedAt`).

### Stock: zero-quantity vs missing

These are distinct test cases:

```java
// Zero-quantity: the part exists at this location but with qty = 0
StockBalance zero = InventoryFixtures.zeroBalance(partId, locationId).build();

// Missing balance: simply don't create a StockBalance row for the (part, location) pair
```

### Password fixtures

| Constant | Value |
|---|---|
| `UserFixtures.TEST_PASSWORD` | `"TestPassword123!"` — document only, never committed as-is |
| `UserFixtures.BCRYPT_HASH_COST12` | BCrypt cost-12 hash — safe to commit |

Always use the hash constant rather than re-hashing in tests to avoid paying the
BCrypt cost on every run.

---

## Seed SQL

`src/test/resources/fixtures/seed-core.sql` inserts shared reference data:

- 4 SLA policy rows (LOW / MEDIUM / HIGH / CRITICAL at `2025-01-01`)
- 1 customer with 2 sites and 3 assets
- 5 parts in the catalogue
- 2 stock locations (central warehouse + site store)

The script is idempotent — run it twice and row counts stay stable.

Load via Spring's `@Sql` or JDBC:

```java
@Sql("/fixtures/seed-core.sql")
class MyIT { ... }
```

---

## Test layers

### Unit tests (no Spring context)

Run with `./mvnw test -pl app -Dtest=!*IT` (excludes integration tests).

- `FixtureBuilderTest` — builder defaults, overrides, lifecycle consistency, determinism
- `FixtureHygieneTest` — scans fixture sources and SQL for non-reserved domains / phones / plaintext passwords

### Integration tests (Testcontainers + PostgreSQL 16)

Run with `./mvnw verify -pl app -Dit.test=FixturePersistenceIT`.

- `FixturePersistenceIT` — persists the dispatch-ready and full-lifecycle scenarios through JPA, asserts all constraints pass; runs seed-core.sql twice to prove idempotency.

---

## Running tests

```bash
# All unit tests (fast)
./mvnw test -pl app

# Integration tests (requires Docker)
./mvnw verify -pl app -Dgroups=integration

# Single fixture IT
./mvnw verify -pl app -Dit.test=FixturePersistenceIT
```

---

## Integration test harness (Testcontainers)

### Quick start

```bash
# Full build: unit + integration + coverage gate
./mvnw verify -pl app

# Integration tests only
./mvnw failsafe:integration-test failsafe:verify -pl app
```

### Base classes

#### `AbstractIntegrationTest` (`com.fieldservice.support`)

Extend for tests that need the full Spring Boot context against real PostgreSQL 16.

```java
@Tag("integration")   // inherited — do not repeat
class MyFeatureIT extends AbstractIntegrationTest {
    @Autowired MyService myService;

    @Test
    void feature_works() { ... }
}
```

**Provides:** singleton PostgreSQL 16 container (started once per JVM), Flyway production
migrations (includes V4 reference data), full Spring context, `mockMvc`, `dataSource`,
`entityManager`, `txManager`, `tx`.

#### `RedisContainerSupport` (`com.fieldservice.support`)

Extend instead of `AbstractIntegrationTest` for tests that exercise Redis-backed
behaviour (caching, rate limiting, refresh-token denylist). All other tests pay zero
Redis startup cost.

### Isolation strategies

| Strategy | When to use | How |
|----------|-------------|-----|
| **Transactional rollback** | Read-only / read-mostly tests | Annotate class/method with `@Transactional` — Spring rolls back automatically |
| **Truncating** | Tests that need real commits (outbox, optimistic lock, conditional UPDATE) | Inject `DatabaseCleaner`, call `cleaner.truncateAll()` in `@AfterEach` |

`DatabaseCleaner.truncateAll()` never removes `sla_policy` or `hold_reason` rows.
Reference data from `V4__seed_reference_data.sql` (`ffffffff-...` UUID space) is
preserved across truncations because those rows are in mutable tables — take care not
to truncate and re-seed them if you write tests against that UUID space.

### Assertion helpers

```java
// Envers revision assertions
AuditAssertions.assertRevisionCount(dataSource, "work_order_aud", woId, 2);
AuditAssertions.assertLatestRevisionType(dataSource, "work_order_aud", woId, REV_UPDATE);

// Outbox event assertions
OutboxAssertions.assertExactlyOne(dataSource, woId, "WORK_ORDER_ASSIGNED");
OutboxAssertions.assertNone(dataSource, woId);  // verify rollback left no trace
```

Revision types: `REV_INSERT=0`, `REV_UPDATE=1`, `REV_DELETE=2`.

### Container reuse (local development)

Add to `~/.testcontainers.properties` to reuse containers across runs:

```properties
testcontainers.reuse.enable=true
```

CI does not have this file, so containers are always fresh in CI.

### Inspecting the test database

```bash
docker ps | grep postgres   # find port
psql -h localhost -p <PORT> -U fsapi -d fsapi_test

# Envers history for a work order
SELECT aud.*, r.actor_user_id FROM work_order_aud aud
JOIN "REVINFO" r ON aud."REV" = r."REV"
WHERE aud.id = '<uuid>';
```

### Coverage report

JaCoCo merges unit and integration runs into a single report:

```
app/target/site/jacoco-aggregate/index.html
```

The build fails when line coverage falls below **80%**. Generate the unit-only report
quickly with:

```bash
./mvnw test jacoco:report -pl app
```

---

## Analytics read-model substrate (WO-161)

### Unit tests

Run the debounce registry unit tests without Docker:

```bash
./mvnw test -pl app -Dtest=MetricDebounceRegistryTest
```

These tests use a `MutableClock` inner class to control wall-clock time deterministically.
No Spring context is loaded.

### Integration tests

The substrate integration test uses Testcontainers PostgreSQL 16:

```bash
./mvnw test -pl app -Dtest=AnalyticsSubstrateIT -Dgroups=integration
```

Requires Docker. Tests verify:
- V24 migration applies cleanly and both `kpi_projection` and `processed_event` tables exist.
- `KpiOutboxConsumer` inserts a `processed_event` row on first delivery.
- Replaying the same `event_id` is a no-op (idempotency).
- `MetricDebounceRegistry` receives enqueued metric keys after consumption.
- `KpiProjectionQuery` returns empty for non-existent projections.
- A manually inserted projection row is readable through the query port with correct `dataAsOf` and `stalenessSeconds`.

### ArchUnit module boundary tests

```bash
./mvnw test -pl app -Dtest=ModuleBoundaryTest
```

These verify that:
- No code outside `analytics` depends on `analytics.internal`.
- The `analytics` module does not reach into `workorder.domain`, `workorder.repository`,
  `inventory.domain`, `inventory.repository`, or `sla.internal`.

### Fixture seed data

`app/src/test/resources/fixtures/seed-analytics.sql` seeds 12 `kpi_projection` rows and
3 `processed_event` rows for multi-month scenario tests. Load it with `@Sql` in integration
tests or via the Flyway test-container bootstrapper.

### Replica DataSource

Configure `app.analytics.replica-url` to point the analytics JdbcTemplate at a read replica:

```yaml
app:
  analytics:
    replica-url: jdbc:postgresql://replica-host:5432/fieldservice
    replica-pool-size: 5
```

Without this property the analytics queries fall back to the primary DataSource automatically.

### Worker profile

The `AnalyticsWorker` scheduler only activates on the `worker` Spring profile.
Run it locally with:

```bash
SPRING_PROFILES_ACTIVE=worker ./mvnw spring-boot:run -pl app
```

Set `app.analytics.enabled=false` to disable the worker without changing profiles.

---

## ArchUnit Fitness Tests

The `com.fieldservice.app.arch` package enforces architectural rules at build time via
[ArchUnit](https://www.archunit.org/). Rules run during `mvn verify` without a Spring
context or database. Full suite completes in under 30 seconds.

### Rule inventory

| ID | File | What it enforces |
|----|------|-----------------|
| **MOD-1** | `ModuleBoundaryTest` | External code must not reach into `analytics.internal.*` |
| **MOD-2** | `ModuleBoundaryTest` | `analytics.*` must not depend on `workorder.domain.*` or `inventory.domain.*` |
| **MOD-3** | `ModuleBoundaryTest` | `analytics.*` must not depend on `aigateway.internal.*` |
| **MOD-4** | `ModuleBoundaryTest` | External code must not reach into `notification.internal.*` |
| **MOD-5** | `AiGatewayBoundaryTest` | Business modules must not reach into `aigateway.internal.*` |
| **L-1** | `LayeredArchitectureTest` | `@RestController` classes must not inject `Repository` types directly |
| **SEC-1** | `MethodSecurityTest` | Every public method on a `@Service` class in `workorder.*` must have `@PreAuthorize` |
| **RS-1** | `ScopedRepositoryFitnessTest` | Repositories over `ScopedEntity` types must extend `ScopedRepository` |
| **CRYPTO-1** | `InjectionAndCryptoRulesTest` | Only reviewed packages may call `MessageDigest.getInstance()` |
| **CRYPTO-2** | `InjectionAndCryptoRulesTest` | `MessageDigest.getInstance()` with MD5/SHA-1/SHA1/DES must not exist outside approved packages |
| **INJECT-1** | `InjectionAndCryptoRulesTest` | No direct `Statement.execute()` / `executeQuery()` calls in production code |
| **DTO-1/2** | `DtoBoundaryTest` | Controller methods must not return or accept `@Entity`-annotated types |
| **INV-1** | `StockLedgerAppendOnlyTest` | No class may call `delete*` on `StockLedgerRepository` |

### Exception process

If a new violation of a rule appears in CI:

1. **Assess**: Is this a genuine bug (missing annotation, wrong layer access) or a justified exception?
2. **Fix preferred**: fix the violation; add the annotation or move the class to the right layer.
3. **If exception is justified**: add a dated comment in the test file (or `frozen-violations/README.txt`
   for frozen rules) and either add an `.ignoreDependency()` call (for `LayeredArchitectureTest`)
   or add the package to the approved list (for `InjectionAndCryptoRulesTest`).
4. **Commit the exception**: the exception is now part of the build; no future team member
   can accidentally remove it without a test failure.

### Self-test fixtures

Deliberately non-compliant classes live in `com.fieldservice.app.arch.fixture` and are
imported into the `DO_NOT_INCLUDE_TESTS`-excluded scope (they are never wired into the Spring
context). Each rule class includes a `*_fires_on_*_fixture` test proving the rule still detects
violations — this prevents silent weakening of a rule through refactoring.

| Fixture | Violation it demonstrates |
|---------|--------------------------|
| `UnprotectedServiceFixture` | `@Service` without `@PreAuthorize` (SEC-1) |
| `NonCompliantWorkOrderRepository` | `JpaRepository` without `ScopedRepository` (RS-1) |
| `ViolatingControllerFixture` | `@RestController` injecting a repository (L-1) |
| `CryptoViolatingFixture` | `MessageDigest.getInstance("MD5")` outside approved package (CRYPTO-1/2) |
| `EntityLeakingControllerFixture` | Controller method returning a JPA `@Entity` (DTO-1) |

### Frozen violations

Pre-existing violations that cannot be fixed immediately are recorded in
`src/test/resources/archunit/frozen-violations/README.txt` with a dated justification
and backlog reference. New violations of a frozen rule still fail the build — only the
exact pairs listed in the test's `.ignoreDependency()` calls are exempt.

Current frozen violations (as of 2026-08-11, backlog BL-2026-001):
- `WorkOrderController` → `WorkOrderRepository` and `WorkOrderHoldRepository` (L-1)
- `WorkOrderPartsController` → `StockLocationRepository` (L-1)
- `InventoryPartsController` → `PartRepository` (L-1)
- `InventoryStockController` → `StockBalanceRepository`, `StockLocationRepository` (L-1)
- `InventoryMovementsController` → `StockLedgerRepository`, `StockLocationRepository` (L-1)

### Running the suite

```bash
# Full verify phase (includes ArchUnit suite, no DB required)
./mvnw verify -pl app -Dmaven.test.skip=false

# ArchUnit tests only (fast)
./mvnw test -pl app -Dtest="*Test,*FitnessTest" -Dgroups="!integration"
```

---

## API Contract Conformance Suite (WO-204)

The `com.fieldservice.contract` package contains a reusable suite that validates the live
HTTP contract for all P0 endpoint groups (auth, work orders, transitions, parts consumption).

### Overview

| Class | P0 Group | Key Coverage |
|-------|----------|-------------|
| `AuthContractIT` | Auth | Happy-path login shape, 401 non-disclosure, 400 field errors, stream-ticket reachability |
| `WorkOrderContractIT` | Work Orders | Empty/populated PagedResponse envelope, final-page no-next-link, size clamping, pagination stability under concurrent mutations |
| `TransitionContractIT` | Transitions | Full response shape, 409 illegal-transition envelope, 409 version-conflict, idempotent replay with exactly-one Envers revision, concurrent 409 |
| `PartsConsumptionContractIT` | Parts | Full response shape, 422 insufficient-stock envelope, balance unchanged on refusal, idempotent replay with exactly-one `stock_ledger` row, idempotency key persisted |
| `OpenApiConformanceTest` | All P0 | Runtime response payloads validated against live `/api-docs` schema: required fields present, field types match declaration, all P0 operation paths present |

### Reusable assertion helpers — `ApiAssertions`

`com.fieldservice.contract.support.ApiAssertions` provides static helpers shared across
all contract tests. Import and use them directly:

```java
import static com.fieldservice.contract.support.ApiAssertions.*;

// Collection envelope (data, page, links)
assertPageEnvelope(result.getResponse());
assertPageMeta(result.getResponse(), 0, 10, 100L);
assertNoNextLink(result.getResponse());
assertEmptyCollection(result.getResponse());

// Error envelope (code, message, fieldErrors, traceId)
assertErrorShape(result.getResponse(), "VALIDATION_FAILED");
assertErrorShape(result.getResponse(), "VALIDATION_FAILED", "email", "password");

// Security — no internal class names, stack traces, or SQL fragments
assertNoInternals(result.getResponse());
assertNoInternals(mvcResult);
```

### Idempotency proofs

AC7 is proven with side-effect counting, not just HTTP status:

| Endpoint | Side-effect table | Assertion |
|----------|-------------------|-----------|
| `POST /work-orders/{id}/transitions` | `work_order_aud` (Envers) | `COUNT(*) = 1` after replay |
| `POST /work-orders/{id}/transitions` | `work_order.version` | `version = 1` after replay |
| `POST /work-orders/{id}/parts` | `stock_ledger` | `COUNT(*) = 1` after replay |
| `POST /work-orders/{id}/parts` | `stock_balance.quantity_on_hand` | exact expected decrement after replay |

### Pagination stability proof (AC5)

`WorkOrderContractIT.pagination_stability_under_concurrent_mutations` inserts 20 work
orders, starts a background `ExecutorService` that inserts 5 more and updates 2 existing
rows during page iteration, then asserts:

- No duplicate IDs appear across any page
- Every original ID appears exactly once in the aggregated result set

### Running the conformance suite

```bash
# Full conformance suite (requires Docker for Testcontainers)
./mvnw verify -pl app -Dgroups=integration -Dtest="*ContractIT,OpenApiConformanceTest"

# Single class
./mvnw verify -pl app -Dit.test=TransitionContractIT
./mvnw test  -pl app -Dtest=OpenApiConformanceTest   # H2 — no Docker needed

# OpenAPI conformance only (no Docker)
./mvnw test -pl app -Dtest=OpenApiConformanceTest
```

### Test isolation

Each `*ContractIT` class owns an isolated PostgreSQL 16 Testcontainers database
(`@Container static PostgreSQLContainer<?>`) with a unique `withDatabaseName(...)` value
so classes can run in parallel without sharing state. `@BeforeEach` inserts fresh rows
with random UUIDs; `@AfterEach` deletes them without truncating the whole schema.

`OpenApiConformanceTest` and `AuthContractIT` use the H2 in-memory test profile
(`@ActiveProfiles("test")`) — no Docker required.

---

## Access Control Test Matrix

The `com.fieldservice.app.security` package contains a declarative, exhaustive test
matrix that verifies every protected endpoint against every role at the HTTP layer.

### Overview

| Class | Responsibility |
|-------|---------------|
| `AccessControlMatrix` | Single source of truth — 29-entry table mapping each endpoint to its permit-role set |
| `AccessControlMatrixTest` | Expands `ENTRIES × 6 probes` into 174 parameterized cases; asserts 401/403/permitted |
| `EndpointCoverageTest` | Enumerates `RequestMappingHandlerMapping`; fails if any non-exempt endpoint lacks a matrix row |
| `TokenShapeNegativeTest` | Lowercase/unknown role values (valid token, wrong authority) → 403 not 401 |
| `MassAssignmentTest` | Unknown fields in POST/PUT bodies → 400, no row written |
| `CrossRoleProbeMatrixTest` | Cross-role HTTP probes: row-scoped visibility, non-disclosure of out-of-scope resources |
| `RbacMatrixTest` | YAML-driven service-layer bypass proof — `@PreAuthorize` intercepted even without HTTP layer |

### Non-disclosure invariant

A caller whose role is not in `permitRoles` receives **HTTP 403** regardless of whether the
target resource exists. There is no "404 for non-members" shortcut. This is tested by
`CrossRoleProbeMatrixTest` for every combination of existing / non-existing resource × role.

### Outcome semantics

| Caller | Expected outcome |
|--------|-----------------|
| No JWT present | **401 Unauthorized** |
| JWT with role not in `permitRoles` | **403 Forbidden** — with `{"code":"FORBIDDEN"}` body |
| JWT with role in `permitRoles` | **Not 401 and not 403** — any domain-level response is acceptable |

The matrix test uses `status().is(s -> s != 401 && s != 403)` for permitted roles so that
validation-level 400 responses from empty `{}` bodies still count as passing the auth gate.

### Token-shape negatives

`TokenShapeNegativeTest` complements `SecurityFilterChainIT` (cryptographic negatives).
It proves that tokens that are cryptographically valid but carry wrong role strings are
still rejected at the authorisation layer:

- `roles: ["dispatcher"]` (lowercase) → `ROLE_dispatcher` → no match → **403**
- `roles: ["SUPERUSER"]` (unknown) → `ROLE_SUPERUSER` → no match → **403**
- `roles: []` (empty list) → no authority → **403**

### Mass-assignment protection

`MassAssignmentTest` proves that Jackson's `FAIL_ON_UNKNOWN_PROPERTIES = true`
(configured globally in `JacksonConfiguration`) is active end-to-end:

- POST body with unknown field → **400**, no row written (verified with `COUNT(*)`)
- Error response body must not contain stack traces or internal class names

### How to add a new endpoint

1. Add a row to `AccessControlMatrix.ENTRIES` with the correct:
   - `method` and `pathPattern` (must match Spring MVC registration exactly)
   - `concretePath` using fixture UUIDs from `db/fixtures.sql`
   - `requestBody` (`null` for GET/DELETE; minimal `{}` for POST/PUT)
   - `permitRoles` set matching the controller's `@PreAuthorize` expression
2. Optionally add the service-layer operation to `src/test/resources/security/rbac-matrix.yml`.
3. Run `EndpointCoverageTest` — it will fail if the `pathPattern` doesn't match a registered handler.
4. If the endpoint is intentionally public (permit-all), add it to `EndpointCoverageTest.EXEMPT_ENDPOINTS`
   with a dated justification comment.

### Running the access-control suite

```bash
# Full matrix test (requires H2 in-memory DB — no Docker)
./mvnw test -pl app -Dtest="AccessControlMatrixTest,EndpointCoverageTest,TokenShapeNegativeTest,MassAssignmentTest"

# Cross-role probe matrix (row-scoped visibility)
./mvnw test -pl app -Dtest=CrossRoleProbeMatrixTest

# RBAC YAML matrix (service-layer bypass proof)
./mvnw test -pl app -Dtest=RbacMatrixTest
```

---

## KPI Baseline Instrumentation Validation (WO-207)

### Metric formulas and denominator exclusions

| Metric | Key | Formula | Zero denominator |
|--------|-----|---------|-----------------|
| SLA compliance | `sla.compliance.rate` | `compliant_closed / total_closed` per priority; ALL = sum-of-priority-numerators / sum-of-denominators | `null` |
| Resolution mean | `sla.resolution.mean` | `AVG(EXTRACT(EPOCH FROM (closed_at - created_at)) / 60)` per priority | `null` |
| Resolution median | `sla.resolution.median` | `PERCENTILE_CONT(0.5)` over same distribution; NOT approximated from mean | `null` |
| Utilization | `workforce.utilization.rate` | `fieldMinutes / shiftMinutes` per technician per ISO week; team = sum-of-numerators | `null` (missing shift); `0.0000` (valid shift, zero labour) |
| First-time fix | `quality.first_time_fix.matured` | `is_first_time_fix_true / classifiable_matured` | `null` |
| Self-service adoption | *(pending)* | `portal_requests / total_requests` | `null` |
| Satisfaction CSAT/NPS | *(pending)* | CSAT: average score; NPS: (promoters − detractors) / total × 100 | `null` |

**Compliance boundary**: `closed_at <= resolution_deadline` is COMPLIANT (inclusive).

**FTF 30-day repeat rule**: links with same `(asset_id, fault_key)` within strictly < 30 days. At exactly 30 days → NOT linked.

**Cohort maturity**: `matured_at = closed_at + 30 days`. PROVISIONAL rows are excluded from `quality.first_time_fix.matured`.

### Golden dataset

Hand-derived expected values are committed at `src/test/resources/golden/kpi-expected-values.json`.
UUID namespace: `gg000000-0000-7207-XXXX-XXXXXXXXXXXX`.

P30D golden dataset (8 work orders):

| Priority | Compliant | Total | Rate | Mean (min) | Median (min) |
|----------|-----------|-------|------|-----------|-------------|
| HIGH | 3 | 4 | 0.7500 | 97.75 | 105.0 |
| MEDIUM | 2 | 3 | 0.6667 | 190.0 | 200.0 |
| LOW | 1 | 1 | 1.0000 | 400.0 | 400.0 |
| ALL | 6 | 8 | 0.7500 | 170.125 | 120.5 |

### Running the validation tests

```bash
# All KPI validation integration tests (requires Docker)
./mvnw test -pl app -Dtest='ComplianceMetricValidationTest,ResolutionTimeValidationTest,UtilizationValidationTest,FirstTimeFixValidationTest' -Dgroups=integration

# Unit-only formula tests (no Spring, no DB)
./mvnw test -pl app -Dtest='ComplianceMetricValidationTest#formula*,UtilizationValidationTest#formula*'
```

### Target configurability

Improvement targets are driven by the `baseline_metric` table. Without a row, `maturity = BASELINE_PENDING`.
After inserting `(metric_key, segment_key, baseline_value)`, maturity transitions to an attainment label (`ON_TRACK`, etc.).
See `ComplianceMetricValidationTest#targetConfig_*` for the executable assertion.

---

## PII masking policy (WO-192)

### Tiers and treatment

| Tier | Treatment | Example fields |
|---|---|---|
| `RESTRICTED` | Fully redacted → `[REDACTED]` | passwordHash, apiKey, jwtSigningKey |
| `CONFIDENTIAL` | Partially masked per strategy | email, phone, fullName, latitude |
| `INTERNAL` | Pass-through | workOrderNumber, equipmentId |
| `PUBLIC` | Pass-through | productName, catalogueCode |

Unclassified fields default to `RESTRICTED` (most restrictive) — zero false-negatives.

### Masking strategies (CONFIDENTIAL per type)

| Type | Strategy | Example output |
|---|---|---|
| EMAIL | Keep first char + domain | `j***@example.com` |
| PHONE | Keep last 2 digits, mask rest | `+44 *** *** **89` |
| NAME | Initials only | `J. S.` |
| ADDRESS | Region component only | `[ADDR]...London` |
| COORDINATE | 1 decimal place (~11 km) | `51.5~` |
| TOKEN / HASH | Full redaction | `[REDACTED]` |

### Adding a new masking strategy

1. Implement `MaskingStrategy` (functional interface, must be idempotent + null-safe).
2. Add a static constant to `MaskingStrategies` with a Javadoc explaining the format.
3. Wire it in `MaskingStrategies.forTier()` if it should be the default for a tier.
4. Add unit tests in `MaskingStrategiesTest` covering null, empty, single-char, malformed.
5. Add a Semgrep allow-list comment if the new strategy introduces a test fixture.

### Fixture scanner

Run:
```
./mvnw test -pl app -Dtest=FixturePiiScannerTest
```

The scanner checks every file under `src/test/resources/fixtures/` for email, phone,
UK postcode and coordinate patterns. Files containing real-looking PII patterns MUST be
in `fixtures/pii-allow-list.txt` with a justification comment.

To add an approved file:
1. Verify it contains only synthetic/fictional values.
2. Add a justification comment + the relative path to `pii-allow-list.txt`.
3. Get sign-off from the privacy team before merging.

### Non-production anonymisation

Activate the `anonymise` profile to pseudonymise a non-production database:
```
APP_ANONYMISE_SEED=<non-prod-only-secret> \
  java -jar app.jar --spring.profiles.active=anonymise
```

The generator:
- Refuses to run if the `prod` profile is active.
- Reads the classification registry to find all CONFIDENTIAL and RESTRICTED entities.
- Replaces CONFIDENTIAL field values with deterministic HMAC-SHA256-derived pseudonyms
  (format-preserving where possible: email → `anon-<hex>@example-anon.invalid`).
- Replaces RESTRICTED column values with `[ANONYMISED-RESTRICTED]`.
- Is idempotent: already-pseudonymised values are not double-processed.
- Uses HmacSHA256 only — MD5, SHA-1 and DES are never used.

### Semgrep PII rules

Run locally:
```
semgrep --config .semgrep/pii-masking-rules.yml app/src/ platform/src/ privacy/src/
```

Rules:
- `logger-logs-classified-field`: direct logger call with a classified field.
- `deprecated-hash-algorithm`: MD5, SHA-1, DES usage.
- `objectmapper-serialises-classified-entity`: bare `new ObjectMapper()` serialising an entity.

### Interpreting a fixture-scanner failure

```
Fixture scanner found PII patterns in non-allow-listed files:
[fixtures/seed-example.sql] found patterns: [EMAIL pattern → [user@real-domain.com]]
```

**Remediation:**
1. Replace the real-looking value with a synthetic one (e.g. `test@example.invalid`).
2. OR add the file to `fixtures/pii-allow-list.txt` if it already contains only fiction.
3. Never use real personal data in test fixtures — even if "anonymised" by hand.
