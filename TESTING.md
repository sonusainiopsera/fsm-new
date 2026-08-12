# Integration Test Harness — Developer Guide

This document covers how to run the integration test suite locally, how to select the right
isolation strategy for a new test, how to use the assertion helpers, and how to inspect coverage
reports.

---

## Quick start

```bash
# Unit tests only (no Docker required)
./mvnw test -pl app

# Unit + integration tests (requires Docker)
./mvnw verify -pl app

# Skip unit tests, run integration tests only
./mvnw verify -pl app -DskipTests -DskipITs=false
```

All integration test classes end in `IT` or `IntegrationTest`. Maven Failsafe runs them in the
`integration-test` phase; Maven Surefire runs everything else.

---

## Prerequisites

- Docker Desktop (or Docker Engine) running locally.
- Java 21+ and Maven 3.9+.
- No network access beyond pulling the `postgres:16-alpine` image the first time.

If Docker is absent, Testcontainers raises `DockerNotAvailableException` on first container start.
The error message names the missing daemon — install Docker and retry.

---

## Container reuse (warm starts)

The `PostgresContainerSupport` base class starts a **singleton** `PostgreSQLContainer` once per JVM
and reuses it across all test classes in the same run. Container reuse across local re-runs is
controlled by the `CI` environment variable:

| Environment       | `CI` variable | Container reuse | Cold-start cost |
|-------------------|---------------|-----------------|-----------------|
| Developer laptop  | unset         | Enabled         | First run only  |
| CI pipeline       | `CI=true`     | Disabled        | Every run       |

When reuse is enabled and the container is already running from a previous `./mvnw verify`, the
second run bypasses the PostgreSQL startup entirely (< 1 s overhead). Cold start is typically
10–20 s depending on hardware.

To force a clean container locally:

```bash
docker stop $(docker ps -q --filter "label=org.testcontainers.sessionId") 2>/dev/null
./mvnw verify -pl app
```

---

## Technician regression suite (WO-160)

### Overview

The technician regression suite validates the full field-execution journey across
frontend and backend boundaries. It is a blocking CI stage with a wall-clock budget
of **≤ 8 min** on a 4-core runner.

### Running locally

**Frontend E2E (Playwright — requires `npm run dev` running):**

```bash
cd field-service-web

# Full technician E2E suite
npx playwright test --project=technician-mobile

# Golden-path spec only
npx playwright test e2e/technician/goldenPath.spec.js --project=technician-mobile

# Accessibility gate only
npx playwright test e2e/technician/accessibility.spec.js --project=technician-mobile

# View report after a run
npx playwright show-report playwright-report
```

**Backend integration (Testcontainers — requires Docker):**

```bash
./mvnw test -pl app -Dtest=TechnicianJourneyIT
```

### Interpreting failures

| Category | What to look at first |
|---|---|
| **Guard refusal (422)** | `traceId` in the response → server log for `LabourTimeRecordedGuard` or `InsufficientStockGuard`. Verify labour_time_record row exists for the work order before COMPLETE. |
| **Conflict (409)** | Current work order state in DB (`SELECT state, version FROM work_order WHERE id = ?`). Illegal transition attempted from the wrong source state. |
| **Idempotency** | Check outbox_event count for the aggregate ID. More than one row for the same event_type means the idempotency filter was bypassed or the Idempotency-Key header was not sent. |
| **Accessibility** | Open the Playwright HTML report (`playwright-report/index.html`). Axe violation shows `id`, `impact`, and `nodes` with CSS selectors. Fix the element referenced in `nodes[0].target`. |
| **Layout (horizontal scroll)** | `document.documentElement.scrollWidth > clientWidth` at 360px. Usually caused by a fixed-width element or `min-width` that exceeds the viewport. Inspect the element at `body > *` level in DevTools. |
| **Touch target** | `boundingBox().height < 44` on a nav item or button. Add `min-height: 44px; min-width: 44px` to the offending component. |
| **Container startup** | Testcontainers log shows `DockerNotAvailableException` or `ContainerLaunchException`. This is infrastructure, not application failure. Restart Docker and retry. |

### Non-flakiness requirement

The suite must pass on **three consecutive CI runs** before merging. Flakiness is
surfaced (not masked) by the `retries: 1` policy — a spec that passes only on retry
is flagged as potentially flaky and should be investigated before the run counts.

---

## Test isolation — choosing a strategy

Two strategies are available. Pick based on whether the test needs real committed rows.

### 1. Transactional rollback (default — read-mostly tests)

Extend `AbstractIntegrationTest` and annotate the test class (or individual test methods) with
`@Transactional`. Spring rolls back after each test. No explicit cleanup required.

```java
@Transactional
class MyServiceTest extends AbstractIntegrationTest {
    @Test
    void shouldComputeSomething() { … }
}
```

Use this for: query tests, service logic that does not involve outbox events or version counters.

### 2. DatabaseCleaner — non-transactional tests

For tests that need real commits (outbox polling, optimistic locking, Envers revisions), do
**not** annotate with `@Transactional`. Call `DatabaseCleaner.clean(jdbc)` in `@AfterEach`.

```java
class MyE2ETest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @AfterEach void clean() { DatabaseCleaner.clean(jdbc); }

    @Test
    void shouldWriteOutboxEvent() { … }
}
```

`DatabaseCleaner.clean()` issues one `TRUNCATE … RESTART IDENTITY CASCADE` statement over all
application tables in dependency order. Reference tables (`role`, `sla_policy`,
`flyway_schema_history`) are excluded and their seed data is preserved.

---

## Assertion helpers

### AuditAssertions — Envers revisions

```java
// Assert a work order has exactly 2 revisions
List<RevisionRecord> revs = AuditAssertions.assertRevisionCount(
        jdbc, "work_order_aud", workOrderId, 2);

// Assert the first revision is an ADD (creation)
assertThat(revs.get(0).revType()).isEqualTo(AuditAssertions.RevisionType.ADD);

// Assert the latest revision is MOD (state change)
AuditAssertions.assertLatestRevisionType(jdbc, "work_order_aud", workOrderId,
        AuditAssertions.RevisionType.MOD);
```

Revision types: `ADD` (0), `MOD` (1), `DEL` (2) — matches `org.hibernate.envers.RevisionType`.

### OutboxAssertions — outbox events

```java
// Assert exactly one event of the given type was committed
OutboxAssertions.assertExactlyOneEvent(jdbc, aggregateId, "WorkOrderStateChanged");

// Assert no event was committed (e.g. after a rolled-back transition)
OutboxAssertions.assertNoEvent(jdbc, aggregateId);

// Retrieve all events for inspection
List<OutboxEventRecord> events = OutboxAssertions.queryEvents(jdbc, aggregateId);
```

Both helpers query over JDBC so they work in non-transactional test contexts where the JPA
session would not yet see the committed rows.

---

## Redis mixin

Tests that exercise cache, rate limiting, or the refresh-token denylist extend
`RedisContainerSupport` (or a class that already extends it):

```java
class RateLimitIT extends RedisContainerSupport {
    // spring.data.redis.host/port are registered automatically
}
```

All other tests extend `AbstractIntegrationTest` and pay no Redis startup cost.

---

## Coverage report

After `./mvnw verify`, the merged HTML report is at:

```
app/target/site/jacoco/index.html
```

Open it in a browser to see line and branch coverage by package. The build fails if the
**line coverage ratio** across the entire `app` module falls below **80 %**. If the check
fails, the build output shows:

```
[ERROR] Rule violated for bundle app: lines covered ratio is X.XX, but expected minimum is 0.80
```

To see which classes are below threshold, open `index.html`, sort by "Missed Lines".

Coverage is collected from two exec files and merged:

| File                         | Source                 |
|------------------------------|------------------------|
| `target/jacoco.exec`         | Surefire (unit tests)  |
| `target/jacoco-it.exec`      | Failsafe (IT tests)    |
| `target/jacoco-merged.exec`  | Merged (report + gate) |

---

## Debugging a failing container

**1. Connection refused at startup**
Testcontainers logs the Docker socket path it is using. Verify Docker is running:

```bash
docker info
```

**2. Flyway migration failure**
The build output will contain `FlywayException` with the offending migration filename and SQL
error. Fix the migration file — do not add a repair migration.

**3. Schema-shape test failure** (`SchemaShapeTest`)
A `@Audited` entity was added without a corresponding Flyway migration that creates the `*_aud`
table, `revinfo`, or `revinfo_seq`. Add the migration and re-run.

**4. Inspect the live test database**

While a test is running (or after a reused container is still up), connect with:

```bash
docker exec -it $(docker ps -q --filter "ancestor=postgres:16-alpine") \
    psql -U test -d fieldservice_test
```

Then inspect with standard `\dt`, `\d tablename`, or `SELECT` queries.

**5. Disable container reuse for one run**

```bash
CI=true ./mvnw verify -pl app
```

This forces a fresh container even on a developer machine.

---

## Redis denylist eviction policy — operational risk (AC-9)

The jti denylist stores short-lived keys in Redis with a TTL equal to the remaining access-token
lifetime. If Redis evicts a denylist key before its TTL expires (e.g. under a `volatile-lru` or
`allkeys-lru` maxmemory policy), the revoked token is silently re-enabled until the TTL is
reached naturally.

**Required Redis configuration:**

```
maxmemory-policy noeviction   # or volatile-ttl — never volatile-lru or allkeys-lru
```

With `noeviction`, Redis returns errors on new writes when memory is full rather than silently
dropping denylist entries. Configure a memory alert (e.g., at 75 % utilisation) so the situation
is addressed before Redis becomes full.

**Monitoring signal for premature eviction:**

The application emits a Micrometer counter `auth.logout.denylist_insert_failure` for every failed
denylist write. A non-zero rate on this counter indicates that Redis rejected the write — either
the store is unreachable or it returned an error (which `noeviction` will do when full). Alert on
`rate(auth.logout.denylist_insert_failure[5m]) > 0`.

Token reuse after logout without a corresponding `denylist_insert_failure` increment would
indicate silent eviction. Cross-reference against `redis_evicted_keys_total` in Prometheus to
detect this scenario.

---

## CI pipeline

The CI environment sets `CI=true`, which disables container reuse. The pipeline runs:

```
./mvnw verify
```

Failsafe runs integration tests, JaCoCo merges the exec files, and the check goal enforces the
80 % threshold. The build fails if the threshold is violated.

Total wall-clock time for the integration suite is printed in the Failsafe summary at the end of
the build.

---

## KPI Baseline Instrumentation Validation (WO-207)

### Metric Formulas and Denominator Exclusions

**Compliance Rate**: `per_priority_rate = compliant_count / total_closed`; ALL rollup = weighted SUM (NOT mean of rates). CANCELLED WOs excluded; null `resolution_due_at` = compliant (BR-21). Boundary: `updated_at <= resolution_due_at` (inclusive ≤).

**Resolution Time**: `mean = AVG(elapsed_minutes)`; `median = percentile_cont(0.5)` — linear interpolation (even n: average of two middle values; odd n: middle value).

**Technician Utilization**: `per_tech = field_minutes / shift_minutes`; team rollup = SUM(field)/SUM(shift) (NOT mean of per-tech rates). Zero shift → `incompleteData=true, value=null`. Over-100% → `degraded=true, DATA_QUALITY_OVERLAP`.

**First-Time-Fix**: repeat-visit window = **strictly less than 30 days** on `(asset_id, fault_key)`. Cohort maturity: `matured_at = closed_at + 30d`; promotion inclusive (`matured_at <= now`). Provisional excluded from matured rate.

**Self-Service Adoption**: `adoption_rate = PORTAL_count / total`. Only `WorkOrderOrigin.PORTAL` counts; `FRONT_OFFICE` and `DISPATCHER` do not. Origin is immutable.

**CSAT / NPS**: CSAT = `AVG(score)` 1–5 scale. NPS = `(promoters% - detractors%) × 100` where promoters = score 9–10, detractors = score 0–6.

### Running KPI Validation Tests

```bash
# All KPI validation tests (unit — no Spring, no DB)
mvn -f app/pom.xml test \
  -Dtest="ComplianceMetricValidationTest,ResolutionTimeValidationTest,UtilizationValidationTest,FirstTimeFixValidationTest,SatisfactionMetricValidationTest,SelfServiceAdoptionValidationTest"
```

### Golden Dataset

Expected values committed in `app/src/test/resources/golden/kpi-expected-values.json`. All values are hand-derived; a test mismatch reports metric name, segment, expected numerator/denominator, and actual.

### Boundary Cases

| Boundary | Test | Expected |
|---|---|---|
| Closure exactly at deadline | `atExactDeadline_isCompliant` | COMPLIANT |
| Closure 1 second after deadline | `oneSecondAfterDeadline_isBreach` | BREACH |
| Repeat visit at 29 days | `gap29Days_isWithinWindow` | Linked |
| Repeat visit at exactly 30 days | `gap30Days_isOutsideWindow` | Not linked |
| Cohort at T+29 days | `closedLessThan30DaysAgo_isProvisional` | PROVISIONAL |
| Cohort at exactly T+30 days | `closedExactly30DaysAgo_isMatured` | MATURED (inclusive) |
| Zero denominators | `zeroDenominator_*` | null (not-available) |
| FRONT_OFFICE not self-service | `frontOffice_notCountedAsSelfService` | rate excludes FO |
| DST-spanning maturity window | `maturityClock_dstSafe_epochSecondsArithmetic` | epoch-seconds invariant |
