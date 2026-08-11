# Backend Testing Guide

## Fixture Library (WO-201)

All test fixtures are in `src/test/java/com/fieldservice/fixtures/`. The library provides:

- **DeterministicIds** — fixed `Clock` (2025-01-15T10:00:00Z) and monotonic UUIDv7 generator
- **UserFixtures** — AppUser builders for all five roles; BCrypt cost-12 password hashing
- **TechnicianFixtures** — Technician + TechnicianCertification builders (current, expiring-soon, expired)
- **WorkOrderFixtures** — WorkOrder builders for all eight lifecycle states
- **CustomerFixtures** — Customer, Site, and Asset builders
- **InventoryFixtures** — Part, StockLocation, and StockBalance builders
- **ScenarioFixtures** — Named multi-aggregate scenarios

An idempotent seed script at `src/test/resources/fixtures/seed-core.sql` covers SLA policy rows, parts catalogue, and a reference customer/site/asset set using `INSERT ... ON CONFLICT DO NOTHING`.

## Determinism Guarantee

Call `DeterministicIds.resetSequence()` before building a scenario. Two builds from the same reset produce byte-identical identifiers, timestamps, and SLA deadlines. This makes ordering, deadline, and pagination assertions stable across machines and CI runs.

```java
@BeforeEach
void reset() {
    DeterministicIds.resetSequence();
}
```

## Test Password

```java
// Documented constant — never commit outside test sources
UserFixtures.TEST_PASSWORD  // "TestFixture@1234!"

// BCrypt cost-12 hash in DelegatingPasswordEncoder format — computed once per JVM
UserFixtures.getTestPasswordHash()  // "{bcrypt}$2a$12$..."
```

## Object-Mother Pattern

Each builder uses static factories with sensible defaults and fluent `with*` overrides:

```java
// One-line fixture for most tests
AppUser dispatcher = UserFixtures.dispatcher().build();

// With overrides
AppUser custom = UserFixtures.technician()
    .withEmail("specific@example.com")
    .withActive(false)
    .build();

// With role assignment
UserFixtures.UserWithRole uwr = UserFixtures.dispatcher().buildWithRole();
entityManager.persist(uwr.user());
entityManager.persist(uwr.roleAssignment());

// Technician with certifications
TechnicianFixtures.TechnicianWithCerts result = TechnicianFixtures.active().build(user);
entityManager.persist(result.technician());
result.certifications().forEach(entityManager::persist);

// Work order in any state
WorkOrder wo = WorkOrderFixtures.inState(WorkOrderState.ON_HOLD)
    .withCustomer(customer)
    .withSite(site)
    .withPriority(WorkOrderPriority.HIGH)
    .build()
    .workOrder();

// Stock: zero balance vs missing row
StockBalance zero    = InventoryFixtures.zeroBalance(partId, locationId);
StockBalance stocked = InventoryFixtures.positiveBalance(partId, locationId, 10);
// "no row" = simply do not create a StockBalance for that (part, location) pair
```

## Named Scenarios

```java
// Dispatch-ready: one NEW HIGH-priority WO, four techs (mixed certs), stocked warehouse
ScenarioFixtures.DispatchReadyScenario scenario = ScenarioFixtures.dispatchReady();

// All eight work order states sharing one customer/site
ScenarioFixtures.AllStatesScenario allStates = ScenarioFixtures.allWorkOrderStates();
```

## Certification Validity Boundaries (relative to DeterministicIds.EPOCH = 2025-01-15T10:00:00Z)

| Template | certType | expiresAt | Valid at EPOCH? |
|---|---|---|---|
| `CURRENT_HVAC` | HVAC | EPOCH + 365d | ✓ Yes |
| `EXPIRING_SOON_HVAC` | HVAC | EPOCH + 7d | ✓ Yes (within 30d warning) |
| `EXPIRED_HVAC` | HVAC | EPOCH − 1d | ✗ No (expired yesterday) |
| `REVOKED_ELECTRICAL` | ELECTRICAL | EPOCH + 275d | ✗ No (revoked = true) |
| `CURRENT_ELECTRICAL` | ELECTRICAL | EPOCH + 305d | ✓ Yes |
| `CURRENT_PLUMBING` | PLUMBING | EPOCH + 335d | ✓ Yes |

## Seed SQL

The reference set at `src/test/resources/fixtures/seed-core.sql` can be loaded via Spring's `@Sql` annotation:

```java
@Sql("classpath:fixtures/seed-core.sql")
class MyIT extends AbstractIntegrationTest { ... }
```

Re-running it on the same database is safe — all inserts use `ON CONFLICT (id) DO NOTHING`.

Fixed IDs in the `ff000000-*` range avoid collisions with V100 fixture UUIDs.

## Hygiene Rules

- All email addresses must use `example.com` or `example.org` (IANA-reserved).
- All phone numbers must use `+15555550xxx` (NANP reserved test range).
- No BCrypt placeholder strings (`$2a$10$...placeholder...`).
- `quantity_reserved` must always be zero (WO-148 constraint).

These are enforced by `FixtureHygieneTest.java`.

## Running Tests

```bash
# Unit tests only (no Testcontainers)
./mvnw -pl app test -Dtest="com.fieldservice.fixtures.*Test"

# Integration tests (Testcontainers PostgreSQL required)
./mvnw -pl app test -Dtest="com.fieldservice.fixtures.*IT"

# All fixture tests
./mvnw -pl app test -Dtest="com.fieldservice.fixtures.**"
```
