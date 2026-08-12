# Load Testing Runbook — Dispatch Recommendation Latency Gate

**WO-206 | Phase 2 Exit Criteria**

## Phase 2 Exit Criteria

This run evidences two stakeholder-confirmed Phase 2 exit criteria:

1. **Recommendation p95 ≤ 3 000 ms** — `GET /api/v1/work-orders/{id}/recommendations` must return within 3 seconds at the 95th percentile for a 200-technician candidate pool.
2. **Server error rate < 1 %** — HTTP 5xx responses must be fewer than 1 % of all recommendation requests under load.

Both thresholds are declared in `app/src/test/load/config/thresholds.json` and evaluated by `ThresholdEvaluator` so the pass/fail decision is code-reviewed, version-controlled, and reproducible.

---

## Local Invocation

### Prerequisites

| Requirement | Detail |
|---|---|
| Java 21 | `java --version` |
| Maven 3.9+ | `mvn --version` |
| Docker | Testcontainers Postgres + WireMock |
| Gatling Maven plugin | In `app/pom.xml` (see `gatling-maven-plugin`) |

### 1. Start the application stack

```bash
# Start PostgreSQL, Redis, and WireMock travel stub via Docker Compose
docker compose -f docker-compose-loadtest.yml up -d

# Wait for health checks
./scripts/wait-for-healthy.sh
```

### 2. Seed the 200-technician pool

```bash
# Generate and apply seed SQL
mvn -f app/pom.xml exec:java \
    -Dexec.mainClass=com.fieldservice.load.LoadSeedGenerator \
    -Dexec.classpathScope=test \
    | psql "$DATABASE_URL" -f /dev/stdin
```

### 3. Run the healthy-provider profile

```bash
mvn -f app/pom.xml gatling:test \
    -Dgatling.simulationClass=load.RecommendationLoadScenario \
    -Dload.profile=healthy \
    -Dload.baseUrl=http://localhost:8080 \
    -Dload.authToken=$(./scripts/mint-dispatcher-token.sh)
```

Results are written to `app/target/gatling/results/`.

### 4. Run the degraded-provider profile

```bash
mvn -f app/pom.xml gatling:test \
    -Dgatling.simulationClass=load.RecommendationLoadScenario \
    -Dload.profile=degraded \
    -Dload.baseUrl=http://localhost:8080 \
    -Dload.authToken=$(./scripts/mint-dispatcher-token.sh)
```

This run configures WireMock with 2 s delay and 80 % fault rate. Assertions verify that:
- Recommendations still return (no 5xx storm)
- `travelEstimateDegraded = true` appears on all responses
- p95 ≤ 3 000 ms (Haversine fallback keeps latency within budget after circuit opens)

### 5. Evaluate thresholds

```bash
mvn -f app/pom.xml exec:java \
    -Dexec.mainClass=com.fieldservice.load.ThresholdEvaluator \
    -Dexec.classpathScope=test \
    -Dexec.args="app/src/test/load/config/thresholds.json app/target/gatling/results/latest/results.json"
```

Exit code 0 = all thresholds pass. Exit code 1 = at least one threshold breached.

---

## CI Invocation

CI runs the load test as a dedicated Maven phase after the integration-test phase:

```bash
mvn -f app/pom.xml verify -Pload-test \
    -Dload.baseUrl=$CI_APP_URL \
    -Dload.authToken=$CI_DISPATCHER_TOKEN
```

The `load-test` Maven profile activates:
1. `gatling:test` (Gatling simulation)
2. `exec:java` (ThresholdEvaluator — fails the build on breach)

---

## Reading Per-Stage Percentiles

Gatling writes per-request-group stats in the HTML report under `app/target/gatling/results/*/index.html`.

| Column | Meaning |
|---|---|
| p50 | Half of requests faster than this. Healthy baseline ≈ 400–800 ms. |
| p90 | 9 in 10 requests faster than this. Healthy baseline ≈ 1 500 ms. |
| **p95** | **Phase 2 gate threshold: must be ≤ 3 000 ms.** |
| p99 | 99th percentile. Threshold ≤ 5 000 ms (catches extreme outliers). |

The **warm-up phase** (first 60 s after ramp) is excluded from percentile assertions. Gatling's `nothingFor` injection defers measurement-window scenarios until warm-up is complete.

---

## Regression Diagnosis Order

When p95 regresses, inspect in this order:

### 1. Cache hit ratio

```bash
curl -s http://localhost:8080/actuator/metrics/geo.travel.cache.hits | jq .
curl -s http://localhost:8080/actuator/metrics/geo.travel.cache.misses | jq .
```

A **cache hit ratio below 80 %** means the travel-matrix cache is cold or evicting. Causes:
- Redis eviction (check `maxmemory-policy`)
- Candidate pool size changed (new coordinate combinations miss the cache)
- TTL too short for the load test hold duration

### 2. Provider call latency

```bash
curl -s http://localhost:8080/actuator/metrics/geo.travel.call.duration | jq '.measurements[0].value'
```

If provider P99 is close to 1 500 ms (the circuit-breaker timeout), the circuit may be flapping. Check:
```bash
curl -s http://localhost:8080/actuator/health/geo.travel.circuit-breaker | jq .
```

### 3. Candidate pool size

The `meta.candidatePoolSize` field in the recommendation response records how many technicians were evaluated. If it drops below the expected 200, eligibility rules are excluding technicians — check certification expiry dates in the seed, and verify the seed SQL was applied.

### 4. Database plan changes

```sql
EXPLAIN (ANALYZE, BUFFERS) 
SELECT ... FROM technician WHERE active = true ...;  -- CandidateReadRepository query
```

A sequential scan on `technician` (instead of index scan) is a common regression cause when the pool grows. Check `idx_technician_active`.

### 5. Connection-pool and virtual-thread saturation

```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending | jq .
```

If `pending > 0` under load, the HikariCP pool is exhausted. This surfaces as scoring slowness, not provider slowness — check `spring.datasource.hikari.maximum-pool-size` in `application.yml`.

Virtual-thread starvation (JVM thread-pinning on synchronized blocks) appears as flat throughput with low CPU. Check thread dumps:
```bash
jcmd $(pgrep -f field-service-api) Thread.print | grep "BLOCKED\|pinned"
```

---

## WireMock Stub Configuration

Stubs are committed at `app/src/test/resources/stubs/travel-matrix/`:

| File | Description |
|---|---|
| `healthy-response.json` | 200 ms delay, success. Used by `healthy` profile. |
| `slow-response.json` | 1 400 ms delay, success. Tests near-timeout behaviour. |
| `degraded-response.json` | 2 100 ms delay, 503 response. Used by `degraded` profile. |

WireMock loads stubs from `src/test/resources/stubs/` automatically when started with `--root-dir`.

---

## Seed Data

The 200-technician seed is generated deterministically by `LoadSeedGenerator`:

```bash
mvn -f app/pom.xml -q exec:java \
    -Dexec.mainClass=com.fieldservice.load.SeedPrinter \
    -Dexec.classpathScope=test > target/load-seed.sql
```

Certification mix in the pool:
- 40 % current HVAC (fully eligible)
- 20 % current ELECTRICAL
- 20 % expiring-soon HVAC (eligible but triggers warning)
- 10 % expired HVAC (ineligible — excluded by eligibility filter)
- 10 % dual-certified (HVAC + ELECTRICAL)

---

## Constraints

- **No real external provider calls.** The travel-time provider stub is mandatory. The `geo.travel.baseUrl` property in `application-loadtest.yml` must point to the WireMock instance.
- **Synthetic data only.** The seed contains no real names, addresses, phone numbers, or coordinates. Phone numbers use the NANP reserved `+1555` range; emails use `.invalid` TLD per RFC 2606.
- **Candidate pool ≥ 200 is documented.** If a run uses more than 200 candidates, the pool size is recorded in `meta.candidatePoolSize` and logged. Silent truncation does not occur — `RecommendationOrchestrator` logs a WARN when truncating.
