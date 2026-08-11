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
