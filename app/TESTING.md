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

## Analytics Module (WO-161)

The analytics substrate uses a separate fixture file and requires Redis for full cache tests.

### Fixture: V111__analytics_fixtures.sql

Located at `app/src/test/resources/db/fixtures/V111__analytics_fixtures.sql`. Seeds:
- 60 historical work orders (80% completed, 20% cancelled) for completion rate queries
- 5 open work orders for backlog count
- 12 completed work orders with `resolution_due_at` for SLA compliance (10 compliant, 2 breached)
- 3 pre-seeded `kpi_projection` rows (maturity=SEEDED) for fast assertion without waiting for refresh cycles

### Running Analytics Tests

```bash
# Unit tests (no Spring, no containers) — debounce, idempotency
mvn -pl app test -Dtest="MetricDebounceRegistryTest,KpiIdempotencyTest"

# Integration tests (PostgreSQL + Redis Testcontainers)
mvn -pl app test -Dtest="com.fieldservice.analytics.internal.KpiAnalyticsIT"

# ArchUnit boundary tests
mvn -pl app test -Dtest="AnalyticsBoundaryTest"

# Full suite
mvn -pl app verify
```

### Replica Datasource in Tests

In test environments `app.analytics.replica-url` is NOT set, so `replicaDataSource` falls back
to the primary Testcontainers datasource. This is expected and logged at WARN level.
Tests assert the `replicaDataSource` bean is present and queryable. In production, set:

```yaml
app:
  analytics:
    replica-url: jdbc:postgresql://replica.internal:5432/fieldservice
    replica:
      username: ${DB_ANALYTICS_USERNAME}
      password: ${DB_ANALYTICS_PASSWORD}
      pool-size: 5
```

### Staleness Budget

The substrate is designed to meet the 60-second p95 freshness requirement:
- Debounce window: 15 seconds
- Cache TTL: 30 seconds
- Replica lag budget: ≤15 seconds

Total worst-case staleness: 15 + 30 + 15 = 60 seconds. The `data_as_of` timestamp
on every projection allows consumers to measure actual staleness and surface it to users.

---

## Privacy Module — Data Classification Registry (WO-188)

### Unit Tests (no Spring context)
```bash
mvn -pl privacy test -Dtest='ClassificationRegistryTest'
```

### Integration Tests (PostgreSQL Testcontainers)
```bash
mvn -pl app test -Dtest='com.fieldservice.privacy.ClassificationApiIT'
```

### ArchUnit Boundary Tests
```bash
mvn -pl app test -Dtest='ScopedRepositoryArchTest,MethodSecurityTest'
```

### Privacy Runbook

#### Tier Definitions
| Tier | Description | Examples |
|------|-------------|---------|
| RESTRICTED | Cryptographic material, credentials | passwordHash, tokenHash, apiKey |
| CONFIDENTIAL | PII and commercially sensitive data | email, customer contact, technician profile |
| INTERNAL | Operational data, no PII | WorkOrder, StockLedger, KPI aggregates |
| PUBLIC | Reference/catalogue data | SlaPolicy, Part catalogue |

#### Adding a New Classified Entity
1. Annotate the entity or field with `@DataClassification(tier = ClassificationTier.XXX)`
2. Add a corresponding row to `data_classification` via a new Flyway migration
3. Run `mvn -pl app verify` — the `ClassificationConsistencyCheck` will fail if any row is missing
4. Restart the application to confirm the check passes

#### Interpreting a Failed Startup Consistency Check
The `ClassificationConsistencyCheck` logs `IllegalStateException: Data classification drift detected:` with:
- **MISSING**: annotated elements with no registry row → add migration rows
- **ORPHANED**: registry rows with no annotation → either re-annotate or add a migration to remove the row

The check runs on all profiles EXCEPT `test`. Set `app.privacy.base-packages` to restrict the scan scope.

---

## ArchUnit Fitness Suite (WO-200)

The ArchUnit suite runs as part of `mvn verify` and enforces structural invariants that are
too subtle or too widespread to catch in code review. All rules produce precise, class-and-method-level
failure messages. The suite completes in well under 30 seconds using a single cached
`ClassFileImporter` per test class.

### Running the Suite

```bash
# Full suite
mvn -pl app verify

# Individual test class
mvn -pl app test -Dtest="LayeredArchitectureTest"
mvn -pl app test -Dtest="ModuleBoundaryTest"
mvn -pl app test -Dtest="MethodSecurityTest"
mvn -pl app test -Dtest="ScopedRepositoryArchTest"
mvn -pl app test -Dtest="InjectionAndCryptoRulesTest"
mvn -pl app test -Dtest="DtoBoundaryTest"

# All architecture tests together
mvn -pl app test -Dtest="*ArchTest,*BoundaryTest,LayeredArchitectureTest,InjectionAndCryptoRulesTest,DtoBoundaryTest"
```

### Rule Catalogue

| Test class | Rule | Covered by AC |
|---|---|---|
| `LayeredArchitectureTest` | No `@RestController` may directly depend on `JpaRepository` | AC-1 |
| `ModuleBoundaryTest` | No class outside module X may access `X.internal` package | AC-2 |
| `MethodSecurityTest` | Every public `@Service` method in business-logic packages has `@PreAuthorize`/`@PostAuthorize` | AC-3 |
| `ScopedRepositoryArchTest` | Repositories over `@ScopedEntity` types must extend `ScopedRepository` | AC-4 |
| `InjectionAndCryptoRulesTest` | No `EntityManager.createNativeQuery`; no `MessageDigest.getInstance` outside approved packages | AC-5, AC-6 |
| `DtoBoundaryTest` | No `@RestController` method may use a `@Entity` type as return or parameter | AC-7 |

### Freeze Store — Pre-existing Exception Process

Some rules have pre-existing violations that were introduced before the rule was enacted.
These are frozen (tolerated) in the freeze store so only **new** violations break the build.

**Store location:** `src/test/resources/archunit/frozen/`

**Frozen violations (baseline 2026-08-11):**

| File | Class | Justification |
|---|---|---|
| `controllers_must_not_access_repositories_directly` | `WorkOrderController` | Predates layering rule; uses `ScopedQueryExecutor` pattern with direct repo injection. Extraction tracked as follow-up. |

#### Adding a New Frozen Exception

> **This process requires explicit team approval.** Frozen exceptions are permanent technical debt
> entries. Exhaust all refactoring options before freezing.

1. Run the suite locally — confirm the new violation is truly pre-existing and not fixable now.
2. Add a dated justification comment in the relevant violation store file.
3. Update this table with the class name and justification.
4. Add a ticket to the backlog to address the violation.
5. Get a second reviewer approval before merging.

#### Repopulating the Store After a Refactor

If a frozen class is refactored (e.g. `WorkOrderController` is split), the freeze store file
may contain stale entries. Remove them, run the suite, confirm it passes, then commit the cleaned
file.

### Approved `MessageDigest` Packages

The following packages are explicitly approved to use `MessageDigest.getInstance` (SHA-256 only):

| Package | Algorithm | Justification |
|---|---|---|
| `com.fieldservice.identity.application` | SHA-256 | Token hash fingerprinting for refresh-token family tracking |
| `com.fieldservice.idempotency` | SHA-256 | Idempotency key hashing for request deduplication |

To add a new approved package: open a security review PR, document the algorithm used, and
add the package to the `APPROVED_DIGEST_PACKAGES` constant in `InjectionAndCryptoRulesTest`.

### Writing a New Rule

1. Add the rule as an `@ArchTest static final ArchRule` field in the appropriate test class.
2. Write a `@Test` self-test that imports a deliberately violating fixture class and asserts failure.
3. Add a compliant fixture class (or assert the production code passes with `rule.check(classes)`).
4. Add a row to the Rule Catalogue table above.
5. If the rule has pre-existing violations, use `FreezingArchRule.freeze(rule)` and populate
   the freeze store file (see above).

### Fixture Package

Deliberately non-compliant classes live in `com.fieldservice.architecture.fixture`. They are:
- In test sources only (excluded from production class scanning via `ImportOption.DoNotIncludeTests`)
- Named `*Fixture` or have an `⚠️ ARCHITECTURE TEST FIXTURE ONLY` header comment
- Never wired as Spring beans

---

## Access-Control Matrix (WO-203)

The access-control matrix is a data-driven, compile-time table of every protected endpoint
with the expected HTTP outcome for each role, including unauthenticated. The matrix is the
single source of truth for role-based access decisions. Adding an endpoint without a matrix
entry fails the build.

### Matrix location

`src/test/java/com/fieldservice/security/AccessControlMatrix.java`

### Adding a new endpoint

1. Locate your controller method in the controller class.
2. Add one `MatrixEntry` to `AccessControlMatrix.entries()`:

```java
entry("my-module.resource.action",
    "POST", "/api/v1/my-module/resources",
    () -> "/api/v1/my-module/resources",
    () -> "{\"field\":\"value\"}",
    Map.of(
        "ADMIN",           201,
        "DISPATCHER",      201,
        "MANAGER",         403,
        "TECHNICIAN",      403,
        "CUSTOMER",        403,
        "UNAUTHENTICATED", 401
    ))
```

3. Run `EndpointCoverageTest` — it will pass once the entry is present.
4. Run `AccessControlMatrixTest` — it will verify the actual HTTP responses match.

### Roles and expected statuses

| Role | Description | Typical scope |
|------|-------------|---------------|
| `ADMIN` | Platform administrator | Full access to all endpoints |
| `DISPATCHER` | Operations dispatcher | Access to work-order lifecycle operations |
| `MANAGER` | Operations manager | Read-only access to work orders, full customer/catalog access |
| `TECHNICIAN` | Field technician | Scoped to assigned work orders only |
| `CUSTOMER` | Customer portal user | Scoped to own account's work orders and sites |
| `UNAUTHENTICATED` | No Bearer token | Must receive 401 for all protected endpoints |

### No-disclosure rule

Endpoints that enforce row-level scope (e.g. work orders, sites, assets) must return the
**same HTTP status** for:
- An out-of-scope record that exists
- A record that does not exist

This prevents callers from enumerating records by probing status code differences.
`AccessControlMatrixTest.forbiddenResponse_isIdentical_forExistingAndNonexistent` verifies this.

### Permit-all justification requirement

Any endpoint declared `permitAll()` in the security filter chain must appear in the matrix
with a `permitAllJustification` comment. The `EndpointCoverageTest.allPermitAllMatrixEntries_haveExplicitJustification`
test enforces this. Permit-all paths must be reviewed by the security team before merging.

### Token factory

Tests use `TestTokenMinter.primary()` to mint real RS256 JWTs signed with `TestRsaKeyPair.PRIMARY`.
The `SecurityFilterChainTestConfig` test configuration wires the matching public key into the
`JwtDecoder` so tokens traverse the full production validation path. **No stub decoder is used.**

### Running the matrix tests

```bash
# Full matrix (endpoint × role) — requires Testcontainers
mvn -pl app test -Dtest="AccessControlMatrixTest"

# Endpoint completeness gate — fails if any endpoint is missing from the matrix
mvn -pl app test -Dtest="EndpointCoverageTest"

# Both together
mvn -pl app test -Dtest="AccessControlMatrixTest,EndpointCoverageTest"
```

---

## P0 API Contract And Envelope Conformance Suite (WO-204)

The conformance suite proves on every build that all P0 endpoints share the same envelope and
error shape, enforce the pagination contract deterministically, honour idempotency keys, reject
unknown properties, and match the published OpenAPI schema.

### Test classes

| Class | What it asserts |
|---|---|
| `com.fieldservice.api.support.ApiAssertions` | Shared helpers: `assertEnvelope`, `assertErrorShape`, `assertPageMeta`, `assertEmptyEnvelope`, `assertLastPage`, `assertNoInternalLeak` |
| `com.fieldservice.api.AuthContractTest` | Login success envelope, invalid-credentials error shape, lockout non-disclosure, strict-schema rejection |
| `com.fieldservice.api.WorkOrderContractTest` | Creation/retrieval contracts, pagination envelope shape, size clamping, sort-field rejection, empty envelope, page-beyond-last, strict-schema, no writable status field, pagination stability (no duplicates) |
| `com.fieldservice.api.TransitionContractTest` | Legal-transition response shape, 409/422 error envelopes, concurrent one-winner, idempotency proof (one revision on replay) |
| `com.fieldservice.api.PartsConsumptionContractTest` | Consumption response shape, 422 error envelope, idempotency proof (one stock-ledger row on replay), strict-schema rejection |
| `com.fieldservice.api.OpenApiConformanceTest` | Live responses satisfy OpenAPI component schemas; no undocumented top-level fields in collection responses |

### Envelope conventions

Every collection endpoint must return:
```json
{
  "data":  [...],
  "page":  { "number": 0, "size": 20, "totalElements": 1234, "totalPages": 62 },
  "links": { "next": "https://…?page=1", "prev": null }
}
```

Every error endpoint must return:
```json
{
  "code":        "STABLE_CODE",
  "message":     "Human-readable message — no resource IDs, no stack traces",
  "fieldErrors": [],
  "traceId":     "uuid-v4"
}
```

### Idempotency proof

The `api` profile activates `IdempotencyKeyFilter`. Tests in `TransitionContractTest` and
`PartsConsumptionContractTest` annotated `@ActiveProfiles("api")` prove:
1. Replay with same `Idempotency-Key` returns the identical response body.
2. Exactly one Envers revision / stock-ledger row exists after the replay (no double-write).
3. A distinct key on the same payload creates a second effect.

### Running the conformance suite

```bash
# All conformance tests (requires Testcontainers PostgreSQL + Redis)
mvn -pl app test -Dtest="AuthContractTest,WorkOrderContractTest,TransitionContractTest,PartsConsumptionContractTest,OpenApiConformanceTest"

# Shared assertion helpers (no Spring context — just compile check)
mvn -pl app test -Dtest="com.fieldservice.api.support.*"

# Full P0 suite including access-control matrix and OpenAPI lint
mvn -pl app verify
```

### Adding a new endpoint to the conformance suite

1. Add a `MatrixEntry` to `AccessControlMatrix` (required by `EndpointCoverageTest`).
2. Add at least one test in the appropriate contract test class asserting:
   - Status code and envelope shape for the happy path.
   - Error envelope for at least one 4xx case.
3. For mutating endpoints, add an idempotency proof using `Idempotency-Key` header.
4. If the endpoint returns a new response type, add it to the OpenAPI `components.schemas`
   and add a schema-conformance assertion in `OpenApiConformanceTest`.

### OpenAPI schema drift detection

`OpenApiSnapshotTest` detects schema drift by comparing the live spec to the committed
snapshot at `src/test/resources/openapi/snapshot.json`. When a response field is added
without updating the snapshot, the build fails with a readable diff.

Regenerate the snapshot after a deliberate API change:
```bash
mvn test -pl app -Dtest=OpenApiSnapshotTest -DUPDATE_SNAPSHOT=true
```
Then commit the updated snapshot and re-run the suite to confirm it passes.
