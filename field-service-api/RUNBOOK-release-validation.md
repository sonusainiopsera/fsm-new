# Release Validation Suite — Runbook

## Purpose

This runbook covers invocation, failure interpretation, rollback decisions, and residue cleanup
for the post-deploy invariant gate suite (`InvariantGateRunner`). The suite is the evidence
consumed by the production promotion gate.

---

## Scope

The suite validates four platform invariants and one smoke path:

| Gate | What it proves |
|---|---|
| `smoke-path` | Application reachable, actuator hardened, auth lifecycle works |
| `audit-revision-gate` | Every state change produces exactly one audit revision |
| `inventory-integrity-gate` | Stock never goes negative; consumption is all-or-nothing |
| `guard-never-fails-open-gate` | 409 for illegal transitions; 422 for cert guard and missing labour |
| `deny-by-default-gate` | 401 unauthenticated; 403/404 with no existence disclosure cross-role |

**Out of scope:** Dashboard first-meaningful-render timing, KPI staleness thresholds,
and front-end accessibility regressions are validated by separate pipelines and are
deliberately not included here. Adding them to this suite would create false failures
on application-tier deployments that do not affect the front-end bundle.

---

## Prerequisites

Before running the suite, ensure the following are in place:

1. **Validation account** — `validator@example.test` (DISPATCHER role) exists in the target
   environment. Created by the seed fixture in `src/test/resources/fixtures/seed-core.sql`.
2. **Admin account** — an ADMIN-role account whose credentials are in the secrets store.
3. **Validation part** — SKU `VAL-PART-001` exists in the inventory with a non-zero balance.
   The fixture seeds this part; replenish with the inventory management endpoint if depleted.
4. **Expired-cert technician** — `expired-cert-tech@example.test` exists and has an expired
   certification in the certification registry (seeded by the fixture).

---

## Environment Variables

All configuration is sourced from environment variables. **No credentials are compiled in.**

| Variable | Required | Description |
|---|---|---|
| `VALIDATION_BASE_URL` | Yes | Base URL, e.g. `https://api.dev.example.com` |
| `VALIDATION_USERNAME` | Yes | Validation account email |
| `VALIDATION_PASSWORD` | Yes | From secrets store |
| `VALIDATION_ADMIN_USERNAME` | Yes | Admin account email |
| `VALIDATION_ADMIN_PASSWORD` | Yes | From secrets store |
| `VALIDATION_RUN_ID` | No | Tag for created records; auto-generated if absent |
| `VALIDATION_DRY_RUN` | No | `true` = read-only gates only (default: `false`) |
| `VALIDATION_OUTPUT` | No | Result artifact path (default: `release-validation-results.json`) |

---

## Invocation by Environment

### Development

```bash
export VALIDATION_BASE_URL=https://api.dev.example.com
export VALIDATION_USERNAME=validator@example.test
export VALIDATION_PASSWORD=$(aws secretsmanager get-secret-value \
    --secret-id dev/validation-password --query SecretString --output text)
export VALIDATION_ADMIN_USERNAME=admin@example.test
export VALIDATION_ADMIN_PASSWORD=$(aws secretsmanager get-secret-value \
    --secret-id dev/admin-password --query SecretString --output text)

java -cp field-service-api-tests.jar \
    com.fieldservice.release.InvariantGateRunner
```

### Staging

Same as dev but with staging secrets and `VALIDATION_RUN_ID=staging-$(date +%Y%m%d-%H%M)`.

### Production

Production runs use **dry-run mode by default** to avoid write side-effects during
business hours, unless explicitly approved for a full validation window:

```bash
# Dry-run (read-only gates only)
export VALIDATION_DRY_RUN=true
java -cp field-service-api-tests.jar \
    com.fieldservice.release.InvariantGateRunner

# Full run (requires approval, use only in maintenance window)
export VALIDATION_DRY_RUN=false
java -cp field-service-api-tests.jar \
    com.fieldservice.release.InvariantGateRunner
```

### CI (automatically run by pipeline)

The CI job sets all environment variables from the pipeline's secrets store and runs:

```bash
mvn test -pl field-service-api \
    -Dtest="InvariantGateSuiteIT,InvariantGateRunnerTest,ResultsArtifactTest"
```

---

## Exit Codes

| Code | Meaning | Pipeline action |
|---|---|---|
| 0 | All gates passed | Promote |
| 1 | Invariant violation or smoke failure | **Block promotion, page on-call** |
| 2 | Environment setup error | Block promotion, alert ops — do not rollback automatically |
| 3 | Transport error (network unreachable) | Block promotion, retry once after 5 minutes |

---

## Gate Failure Interpretation

### `smoke-path` failures

| Symptom | First response |
|---|---|
| Health returns DOWN | Check database and Redis connectivity; check recent migration | **Immediate rollback** |
| Actuator endpoint exposed (`/actuator/env`, `/actuator/heapdump`) | Security misconfiguration; check `management.endpoints.web.exposure.include` | **Immediate rollback** |
| Login returns 401 | Validation account missing or wrong password; check secrets store | Ops investigation |
| Auth endpoints return 404 | Auth module not wired; check bean registration | **Immediate rollback** |
| Login exceeds 2000ms budget | DB latency spike; check connection pool | Investigate before promoting |

### `audit-revision-gate` failures

| Symptom | First response |
|---|---|
| Zero new revisions after state change | Envers listener not firing; check `@Audited` annotation and `notification_preference_AUD` / work-order audit tables | **Immediate rollback** |
| More than one revision | Duplicate listener registration; check Spring context | Investigate |
| Revision missing state field | DTO projection incomplete | Investigate |

### `inventory-integrity-gate` failures

| Symptom | First response |
|---|---|
| Over-consumption accepted (2xx) | `CHECK (quantity_on_hand >= 0)` constraint dropped or conditional UPDATE missing | **Immediate rollback** |
| Balance unchanged after refused (setup error) | Validation part depleted; replenish `VAL-PART-001` | Ops: replenish and re-run |
| Balance wrong after success | Decrement arithmetic error | **Immediate rollback** |

### `guard-never-fails-open-gate` failures

| Symptom | First response |
|---|---|
| Illegal transition accepted (2xx) | Transition guard removed or bypassed | **Immediate rollback** |
| Completion without labour accepted | Labour-time precondition guard missing | **Immediate rollback** |
| Expired-cert assignment accepted | Certification guard missing or not enforced for ADMIN | **Immediate rollback** |
| Returns wrong error code (e.g. 400 instead of 409) | Guard present but returning wrong HTTP status | Investigate; may not need rollback |

### `deny-by-default-gate` failures

| Symptom | First response |
|---|---|
| Unauthenticated probe returns 2xx | Security filter chain misconfigured | **Immediate rollback** |
| Cross-role returns 2xx | Row-scope predicate missing from repository query | **Immediate rollback** |
| Cross-role and nonexistent return different codes | Existence disclosure regression | **Immediate rollback** |

---

## Rollback Triggers

Immediate rollback (pipeline should call canary rollback automatically on exit code 1):

- Health DOWN
- Any actuator endpoint unexpectedly exposed
- Auth endpoints missing (404)
- Any invariant gate returns FAIL

**Do not automatically rollback on:**

- Exit code 2 (setup error) — the deployment may be fine; ops investigation first
- Exit code 3 (transport) — retry after 5 minutes; may be a transient network partition

---

## Cleaning Up Residue After an Aborted Run

If the suite is interrupted (e.g. SIGKILL), cleanup may not complete. To clean up manually:

```bash
# Find all work orders tagged with the aborted run ID
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "https://api.example.com/api/v1/work-orders?validationRunId=<run-id>&size=50"

# Cancel each returned work order
curl -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"state":"CANCELLED"}' \
  "https://api.example.com/api/v1/work-orders/<id>/state"
```

The `runId` is included in the result artifact (`release-validation-results.json`) under
the `runId` field for traceability.

---

## Concurrent Runs

The `runId` is unique per invocation (UUID-suffixed). Two concurrent suite runs against
the same environment will not interfere because:

1. Created records are tagged with different run identifiers.
2. Cleanup queries are scoped to the run identifier.
3. The inventory gate uses a dedicated part (not work-order state), and consuming 1 unit
   twice is handled by the stock guard.

---

## Idempotency

Running the suite twice against the same environment leaves no accumulating residue:

1. All created work orders are cancelled before the runner exits.
2. The second run creates new records with a new `runId` — it does not reuse or mutate
   records from the first run.
3. The inventory gate consumes 1 unit per run — the validation part must have sufficient
   stock for repeated runs (replenish if needed; see Prerequisites).
