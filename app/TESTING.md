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
