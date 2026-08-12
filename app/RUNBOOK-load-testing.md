# Load Testing Runbook — Dispatch Recommendation Endpoint

## Phase 2 Exit Criteria

These are the committed, executable gates for Phase 2 sign-off:

| Criterion | Threshold | Metric key in results.json |
|---|---|---|
| Recommendation p95 latency | **≤ 3 000 ms** | `recommendations.latency.p95.ms` |
| Server error rate (5xx) | **< 1%** | `all.error.rate.percent` |

Both thresholds are enforced by `ThresholdEvaluator` reading `thresholds.json`. The CI build
exits non-zero if either criterion is breached.

---

## Running the Load Test

### Prerequisites

1. **Application running** — start the API with the load-test profile:
   ```
   java -jar target/app.jar --spring.profiles.active=test,load-test \
     -DTRAVEL_PROVIDER_BASE_URL=http://localhost:8099 \
     -DTRAVEL_PROVIDER_ALLOWED_HOST=localhost
   ```

2. **Travel provider stub running** — start WireMock with the committed stubs:
   ```
   java -jar wiremock-standalone.jar \
     --port 8099 \
     --root-dir app/src/test/resources/stubs
   ```
   Use `stubs/travel-matrix/success.json` for the healthy profile.
   Use `stubs/travel-matrix/degraded-provider.json` for the degraded profile.

3. **Database seeded**:
   ```sql
   \i app/src/test/resources/fixtures/seed-core.sql
   \i app/src/test/resources/fixtures/seed-load-200-technicians.sql
   ```

4. **Authentication token** — obtain a dispatcher JWT and pass it:
   ```
   -Dload.dispatcherToken=<jwt>
   ```

### Healthy run (Phase 2 gate)

```
mvn gatling:test -Dgatling.simulationClass=load.RecommendationLoadScenario \
  -Dload.profile=healthy \
  -Dload.baseUrl=http://localhost:8080
```

### Degraded-provider run

```
mvn gatling:test -Dgatling.simulationClass=load.RecommendationLoadScenario \
  -Dload.profile=degraded \
  -Dload.baseUrl=http://localhost:8080
```

Replace the WireMock stub with `degraded-provider.json` before running.

### CI smoke run (fast)

```
mvn gatling:test -Dgatling.simulationClass=load.RecommendationLoadScenario \
  -Dload.profile=ci-smoke
```

### Evaluate thresholds

After any run, evaluate the generated results file:

```
mvn test -Dtest=none -pl app \
  -Dload.thresholdsFile=src/test/load/config/thresholds.json \
  -Dload.resultsFile=target/load-results/results.json \
  -Dload.profile=healthy
```

Or run `ThresholdEvaluator.main()` directly:
```
java -cp app/target/test-classes:app/target/classes \
  com.fieldservice.load.ThresholdEvaluator \
  src/test/load/config/thresholds.json \
  target/load-results/results.json \
  healthy
```

---

## Reading Per-Stage Percentiles

The Gatling HTML report at `target/gatling/*/index.html` shows per-scenario percentile distributions. When reading the results:

- **Warm-up phase** (first `warmUpSeconds` seconds): discard these — caches are cold and latency is elevated. The Phase 2 gate applies only to the **hold phase**.
- **Ramp phase**: latency will decrease as caches warm. Not representative of steady-state.
- **Hold phase**: the Phase 2 gate percentiles. Recorded in `target/load-results/results.json`.
- **Cool-down phase**: latency may increase as in-flight requests finish without new work arriving. Discard.

The `results.json` file is written by the custom Gatling listener `LoadResultsWriter` and contains:
```json
{
  "recommendations.latency.p50.ms": <value>,
  "recommendations.latency.p90.ms": <value>,
  "recommendations.latency.p95.ms": <value>,
  "recommendations.latency.p99.ms": <value>,
  "all.latency.p95.ms": <value>,
  "all.error.rate.percent": <value>,
  "all.throughput.rps": <value>,
  "error.count.5xx": <value>,
  "travel.cache.hit.ratio": <value>,
  "provider.latency.p95.ms": <value>,
  "pool.size.used": 200,
  "warmup.boundary.seconds": <warmUpSeconds>
}
```

---

## Regression Diagnosis Order

When p95 regresses, investigate in this order:

### 1. Travel matrix cache hit ratio

Check `travel.cache.hit.ratio` in results.json. If it dropped below 0.5:
- The Redis TTL may have been shortened — check `geo.travel.cache.ttl-seconds`.
- The cache key may have changed (coordinate precision) — check `geo.travel.cache.coordinate-precision-decimal-places`.
- Redis may be unavailable — check the Redis health indicator at `/actuator/health`.

### 2. Travel provider latency

Check `provider.latency.p95.ms`. If it increased:
- The WireMock stub fixed delay may have drifted — re-check `fixedDelayMilliseconds` in `success.json`.
- In production: check provider rate limits or routing changes.

### 3. Candidate pool size

Check `pool.size.used`. If it is less than 200:
- The seed may not have been applied — rerun `seed-load-200-technicians.sql`.
- Active technician count may have been reduced — check `technician.active = TRUE` count.

### 4. Database query plan change

Run `EXPLAIN ANALYZE` on the eligibility filter query:
```sql
EXPLAIN ANALYZE
SELECT t.id FROM technician t
JOIN technician_certification tc ON tc.technician_id = t.id
WHERE t.active = TRUE
  AND tc.certification_code = 'ELEC_DISPATCH'
  AND (tc.expires_on IS NULL OR tc.expires_on > NOW());
```

Look for sequential scans on `technician_certification`. The index
`idx_tech_cert_code_expiry` should be used. If not, run `ANALYZE technician_certification`.

### 5. Connection-pool saturation

Check `hikaricp.connections.active` and `hikaricp.connections.pending` in Prometheus
(`/actuator/prometheus`). If connections are frequently pending:
- Confirm HikariCP is configured at `maximumPoolSize = 20`.
- Confirm virtual threads are enabled: `spring.threads.virtual.enabled = true`.
- A queue depth > 0 for more than 5 seconds indicates pool exhaustion; the result
  will appear as scoring slowness but the root cause is concurrency, not scoring.

### 6. JVM saturation

Check `jvm.threads.live` and `jvm.memory.used`. Virtual thread pinning (e.g., inside
a `synchronized` block) appears as platform thread exhaustion. Run a JFR recording:
```
jcmd <pid> JFR.start duration=60s filename=load.jfr
```

---

## Degraded-Provider Run Interpretation

In the degraded-provider run:

- `recommendations.degraded.flag.rate` should be ≥ 0.9 — verifying that the circuit
  breaker opened and Haversine fallback is serving estimates.
- `recommendations.latency.p95.ms` must still be ≤ 3 000 ms — Haversine is O(n) in
  the candidate pool and should be significantly faster than the provider timeout.
- If p95 exceeds the threshold in degraded mode, the Haversine path is on the critical
  path — check for synchronized blocks or blocking I/O in `HaversineEstimator`.

---

## Candidate Pool

The seed `seed-load-200-technicians.sql` provides 200 technicians:

| Range | Cert status | Active | Expected recommendation outcome |
|---|---|---|---|
| 1–140 | Valid ELEC_DISPATCH | Yes | **Eligible** — appear in results |
| 141–165 | Expired ELEC_DISPATCH | Yes | Excluded: CERTIFICATION_EXPIRED |
| 166–185 | No cert | Yes | Excluded: CERTIFICATION_MISSING |
| 186–200 | — | No | Excluded: INACTIVE_TECHNICIAN |

Effective eligible pool: **140 technicians**.

If the eligible pool size drops (e.g., to 50), scoring runs faster but is not
representative of production load — the latency gate may pass when it should not.
The `pool.size.used` field in results.json records the actual pool at run time.

---

## Committed Seed Sources

| File | Purpose |
|---|---|
| `src/test/resources/fixtures/seed-load-200-technicians.sql` | 200-technician position/cert fixture |
| `src/test/resources/stubs/travel-matrix/success.json` | Healthy travel provider WireMock stub |
| `src/test/resources/stubs/travel-matrix/degraded-provider.json` | Degraded travel provider stub |
| `src/test/load/config/profiles.json` | Ramp / hold / warm-up profile configuration |
| `src/test/load/config/thresholds.json` | Pass/fail threshold configuration |
| `src/test/load/RecommendationLoadScenario.java` | Gatling Java simulation |

All seed data is synthetic. No real names, addresses, phone numbers, or GPS coordinates.
