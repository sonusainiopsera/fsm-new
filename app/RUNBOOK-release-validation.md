# RUNBOOK: Post-Deploy Invariant Gate and Smoke Validation Suite

**Version:** 1.0  
**Scope:** WO-205  
**Applies to:** dev, staging, production environments  

---

## Overview

The post-deploy validation suite (`InvariantGateRunner`) runs after every deployment to verify that the four core platform invariants still hold in the deployed environment. It also exercises a thin smoke path through the public API.

**Scope boundary:** This suite deliberately does NOT validate dashboard first-meaningful-render times, KPI staleness, or frontend rendering. Those are validated by a separate frontend smoke suite.

Exit code `0` = all gates passed. Exit code `1` = one or more gates failed. Exit code `2` = configuration error.

---

## Prerequisites

- The target environment must have a dedicated validation account: `dispatcher@example.com`
- The validation account credentials must be available in the secrets store
- The environment must be reachable at the configured base URL
- For write-mode runs: the inventory validation part (`00000000-0000-0000-0000-000000000099`) must exist with positive stock balance

---

## Invocation

### Required environment variables

| Variable | Description |
|---|---|
| `VALIDATION_BASE_URL` | Base URL of the target environment, e.g. `https://api.dev.example.com` |
| `VALIDATION_ACCOUNT_EMAIL` | Login email for the validation account (usually `dispatcher@example.com`) |
| `VALIDATION_ACCOUNT_PASSWORD` | Password — **never hard-code; always load from secrets store** |
| `VALIDATION_RUN_ID` | Unique run identifier. Use `git-SHA-timestamp`, e.g. `abc1234-20260101T120000Z` |

### Optional environment variables

| Variable | Default | Description |
|---|---|---|
| `VALIDATION_DRY_RUN` | `false` | Set to `true` to execute read-only gates only |
| `VALIDATION_ARTIFACT_PATH` | `./validation-results.json` | Path for the JSON results artifact |

### Running against dev

```bash
export VALIDATION_BASE_URL="https://api.dev.example.com"
export VALIDATION_ACCOUNT_EMAIL="dispatcher@example.com"
export VALIDATION_ACCOUNT_PASSWORD="$(op read 'op://vault/fieldservice-dev/dispatcher-password')"
export VALIDATION_RUN_ID="$(git rev-parse --short HEAD)-$(date -u +%Y%m%dT%H%M%SZ)"
java -cp field-service-api.jar com.fieldservice.release.InvariantGateRunner
```

### Running against staging

```bash
export VALIDATION_BASE_URL="https://api.staging.example.com"
export VALIDATION_ACCOUNT_EMAIL="dispatcher@example.com"
export VALIDATION_ACCOUNT_PASSWORD="$(op read 'op://vault/fieldservice-staging/dispatcher-password')"
export VALIDATION_RUN_ID="$(git rev-parse --short HEAD)-$(date -u +%Y%m%dT%H%M%SZ)"
java -cp field-service-api.jar com.fieldservice.release.InvariantGateRunner
```

### Running in dry-run mode (production — suppresses write gates)

```bash
export VALIDATION_BASE_URL="https://api.example.com"
export VALIDATION_ACCOUNT_EMAIL="dispatcher@example.com"
export VALIDATION_ACCOUNT_PASSWORD="$(op read 'op://vault/fieldservice-prod/dispatcher-password')"
export VALIDATION_RUN_ID="$(git rev-parse --short HEAD)-$(date -u +%Y%m%dT%H%M%SZ)"
export VALIDATION_DRY_RUN="true"
java -cp field-service-api.jar com.fieldservice.release.InvariantGateRunner
```

---

## Gate-by-gate failure interpretation

### SmokePath.health — FAIL

**Symptom:** `Health check returned HTTP <status>` or `status is 'DOWN'`  
**Root cause:** The application or a dependency (database, Redis) is not healthy.  
**First response:** Check the container logs. Check the database connection pool. Check that dependent services are running.  
**Rollback trigger:** YES — if health returns DOWN, the deployment is not functional.

---

### SmokePath.actuatorHardening — FAIL

**Symptom:** `/actuator/env`, `/actuator/heapdump`, or `/actuator/loggers` returned non-404.  
**Root cause:** An actuator endpoint that must be disabled has been accidentally exposed. This is a security regression.  
**First response:** Check `management.endpoints.web.exposure.include` in the deployed `application.yml`. Verify the configuration matches the expected hardened profile.  
**Rollback trigger:** YES — exposing sensitive actuator endpoints is an immediate security regression.

---

### SmokePath.login — FAIL

**Symptom:** `Login returned HTTP 401` or `Login response missing accessToken field`  
**Root cause:** The validation account credentials are wrong, the account has been deactivated, or the auth service is not running.  
**First response:** Verify the `VALIDATION_ACCOUNT_EMAIL` and `VALIDATION_ACCOUNT_PASSWORD` env vars. Check the auth service logs.  
**Rollback trigger:** INVESTIGATE first — this may be a configuration issue rather than a code regression.

---

### SmokePath.refresh — FAIL

**Symptom:** `Token refresh returned HTTP <status>`  
**Root cause:** The token refresh endpoint is broken. This could be a JKT rotation issue, a Redis connectivity problem, or a code regression.  
**First response:** Check the auth service logs for the refresh endpoint. Verify Redis is accessible.  
**Rollback trigger:** YES if caused by a code regression. NO if caused by Redis connectivity.

---

### SmokePath.happyPath — FAIL

**Symptom:** Various — check the detail field for which step failed.  
**Root cause:** The most recently deployed code broke work-order creation, assignment or transitions.  
**First response:** Check the application logs for the failing endpoint. The detail field includes the HTTP status code and the step that failed.  
**Rollback trigger:** YES if the failing step was present in a prior successful run.

---

### SmokePath.cleanup — FAIL

**Symptom:** `Cancel returned HTTP <status>` for the created work order.  
**Root cause:** The validation work order could not be cancelled. This leaves residue but is **not** a gate failure for the rollback decision.  
**First response:** Manually cancel the work order tagged with the run ID. Use the `VALIDATION_RUN_ID` to locate it:
```
GET /api/v1/work-orders?title=Validation-<RUN_ID>
```
**Rollback trigger:** NO — cleanup failure is reported separately. Check gate failures for rollback decisions.

---

### AuditRevisionGate — FAIL

**Symptom:** `No revisions found` or `Revisions do not include state 'ASSIGNED'`  
**Root cause:** The Envers audit listener has been removed, the `*_aud` table is missing, or the revision is not being committed in the same transaction as the state change.  
**First response:** Check the Flyway migration history for the `work_order_aud` table. Check the application logs for Envers errors.  
**Rollback trigger:** YES — missing audit revisions is a compliance violation and an immediate-rollback trigger.

---

### InventoryIntegrityGate — FAIL

**Symptom:** `Over-consumption was accepted` or `Balance changed after refused consumption`  
**Root cause:** The stock floor guard has been removed or bypassed. Negative stock is possible.  
**First response:** Check the inventory service code for the stock guard. Check if a recent change removed the floor check.  
**Rollback trigger:** YES — stock non-negativity is a financial invariant.

**Symptom:** `SETUP_ERROR: validation part has zero stock`  
**Root cause:** The validation part has been depleted by previous runs or by real consumption.  
**First response:** Replenish the validation part stock via the admin API. This is NOT a code regression.  
**Rollback trigger:** NO — ops task.

---

### GuardNeverFailsOpenGate — FAIL

**Symptom:** `Illegal transition was accepted (HTTP 200)` or `COMPLETE without labour time accepted`  
**Root cause:** A lifecycle guard has been removed or its throw-on-violation code has been bypassed.  
**First response:** Check the `WorkOrderTransitionTable` guard configuration. Check if a recent change removed a guard ID from the transition table.  
**Rollback trigger:** YES — guards failing open allows data integrity violations.

---

### DenyByDefaultGate — FAIL

**Symptom:** `Unauthenticated probe returned HTTP <non-401>` or `Cross-role probe returned HTTP 200`  
**Root cause:** An access control predicate has been removed or misconfigured.  
**Symptom:** `Existence disclosure detected: real resource returns 403, nonexistent returns 404`  
**Root cause:** The scope predicate returns a different error for in-scope vs. out-of-scope resources.  
**First response:** Check `ScopedEntityPredicateProvider` for the affected entity. Check `ScopedQueryExecutor` error handling.  
**Rollback trigger:** YES — access control regression is an immediate-rollback trigger.

---

## Cleaning up residue after an aborted run

If the suite exits before the cleanup step (e.g. killed by SIGKILL, network partition), work orders created by the aborted run will remain in a non-terminal state.

To clean up manually:

1. Find all work orders tagged with the run ID:
```
GET /api/v1/work-orders?search=<RUN_ID>
```

2. For each work order in a non-terminal state (not CANCELLED, CLOSED, COMPLETED), post a CANCEL transition:
```
POST /api/v1/work-orders/{id}/transitions
{"event": "CANCEL", "reason": "manual-cleanup-after-aborted-run-<RUN_ID>"}
```

---

## SETUP_ERROR vs FAIL distinction

| Status | Meaning | Action |
|---|---|---|
| `PASS` | Invariant held | No action |
| `FAIL` | Invariant violated | Investigate and rollback if caused by code regression |
| `SKIP` | Gate not executed (dry-run mode) | No action; not a pass |
| `SETUP_ERROR` | Environment misconfigured or prerequisite missing | Fix environment; NOT a code regression |

`SETUP_ERROR` and `FAIL` both cause `overallPass=false` and exit code `1`. They are distinguished in the artifact so operators can identify environment issues without triggering a code rollback.

---

## Running in CI (self-test mode)

The `InvariantGateSelfTest` class exercises the full suite against a Testcontainers-backed application. It runs automatically as part of the `failsafe` Maven plugin lifecycle:

```bash
./mvnw verify -pl app -Pfailsafe
```

The self-test asserts:
- All gates pass on a healthy build
- Dry-run mode marks write gates as SKIP
- Running twice leaves no accumulating residue
- The JSON artifact has the required schema and contains no credentials
