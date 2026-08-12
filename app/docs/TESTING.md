# Technician Field Execution — Testing Guide

## Overview

This guide covers the end-to-end regression suite (WO-160) for the technician
field execution journey. The suite spans two layers:

| Layer | Location | Runner |
|---|---|---|
| Frontend e2e (Playwright) | `field-service-web/e2e/technician/` | `npm run test:e2e:technician` |
| Backend integration (Testcontainers) | `app/src/test/java/.../workorder/integration/` | `mvn test -Dgroups=integration` |

---

## Running the suite locally

### Prerequisites

- Node ≥ 20, npm installed
- Java 21, Maven installed
- Docker running (required for Testcontainers)
- Playwright browsers installed: `cd field-service-web && npx playwright install chromium`

### Frontend e2e tests

```bash
cd field-service-web

# Start the dev server in one terminal (tests use it via webServer config)
npm run dev

# In another terminal — full mobile regression suite
npm run test:e2e:technician

# Individual spec files
npx playwright test --project=technician-mobile e2e/technician/goldenPath.spec.js
npx playwright test --project=technician-mobile e2e/technician/refusals.spec.js
npx playwright test --project=technician-mobile e2e/technician/accessibility.spec.js

# Open HTML report after a run
npx playwright show-report test-results/playwright-html
```

### Backend integration tests

```bash
cd app

# Run only integration tests
mvn test -Dgroups=integration

# Run only the journey IT
mvn test -pl . -Dtest=TechnicianJourneyIT
```

### Parallel CI run

Both suites are independent. Run them in parallel in CI:

```yaml
# Example GitHub Actions parallel matrix
strategy:
  matrix:
    suite: [frontend-e2e, backend-it]
```

---

## Failure triage by category

### Guard refusal (422 `GUARD_REFUSED`)

**Symptoms:** Test asserts 422 but gets 200, or guard code is wrong.

**First inspection:**
1. Check `guardCode` in the response body — it names the specific guard.
2. Common codes: `LABOUR_TIME_MISSING` (complete without labour), `HOLD_REASON_MISSING`.
3. Verify the fixture inserted the expected pre-conditions (labour entry, hold reason code).
4. Check `LabourTimeRecordedGuard` / `HoldReasonRequiredGuard` for logic regressions.

### Conflict / illegal transition (409 `ILLEGAL_TRANSITION`)

**Symptoms:** Test expects a transition to succeed but gets 409, or vice versa.

**First inspection:**
1. Check `WorkOrderTransitionTable` — the allowed (from, event) pairs are the canonical source.
2. Verify the fixture puts the work order in the right starting state.
3. If `expectedVersion` in the request doesn't match the DB row, the transition is rejected with 409 — check if another test modified the same row.

### Cross-technician security / 403

**Symptoms:** Test expects 403 but gets 200 or 404.

**First inspection:**
1. Check `WorkOrderScopeSpec` — the scope predicate that restricts technician rows.
2. Verify the JWT claims in the test token include the correct `technician_id`.
3. A 404 instead of 403 means existence disclosure — check the controller exception handler.

### Idempotency failure

**Symptoms:** Second replay gets 409 instead of the same 200, or state advances twice.

**First inspection:**
1. Check `IdempotencyFilter` — the platform filter stores responses keyed by `Idempotency-Key` header.
2. Verify both requests carry the exact same `Idempotency-Key` header value.
3. If the second request advances state, the filter is not replaying from cache — check TTL expiry and filter registration order.

### Accessibility violation (axe critical/serious)

**Symptoms:** axe scan reports a violation that wasn't present before.

**First inspection:**
1. The violation output includes the HTML node — find it in the component tree.
2. Common causes: missing `aria-label` on icon-only buttons, colour contrast regression after a token change, focus order broken by a CSS `order` change.
3. Run `npx axe /technician` in a local browser with the axe CLI to iterate quickly.

### Layout / touch target failure

**Symptoms:** `scrollWidth > clientWidth` assertion fails, or a button is reported < 44 px.

**First inspection:**
1. Horizontal scroll: check the recently added component for `width: 100%` in a flex container without `min-width: 0`, or hard-coded pixel widths wider than 360 px.
2. Touch target: check `TechnicianShell.module.css` — all interactive elements need `min-width: 44px; min-height: 44px`.

### Container startup failure

**Symptoms:** `TechnicianJourneyIT` fails in the `@Container` setup before any test runs.

**First inspection:**
1. Docker must be running — `docker ps` to confirm.
2. If `postgres:16-alpine` pull fails, check Docker registry access.
3. If Flyway migration fails, a recent migration script has a syntax error — check `app/src/main/resources/db/migration/` for the latest `Vxx__*.sql` file.

---

## Wall-clock budget

| Suite | Expected wall-clock | Budget ceiling |
|---|---|---|
| Frontend e2e (`technician-mobile`) | ~90 s | 180 s |
| Backend IT (`TechnicianJourneyIT`) | ~60 s | 120 s |

If a run exceeds the budget, check:
- Container startup time (Testcontainers first-run pulls add ~30 s)
- `waitForLoadState('networkidle')` calls in Playwright — these are safe but can hang if a route never settles

---

## Fixtures

| File | Purpose |
|---|---|
| `app/src/test/resources/fixtures/seed-journey.sql` | Two technicians, 8 work orders in every state, one labour entry for JRN-003 |
| `app/src/test/resources/fixtures/seed-technician-day.sql` | Technician day-list fixture (WO-154) |
| `field-service-web/src/mocks/fixtures/technician/day-list.json` | MSW fixture for frontend unit tests |

All fixtures are idempotent (`ON CONFLICT DO NOTHING`) and use fully synthetic, anonymised data. No real personal data is committed.

---

## Non-flakiness validation

The WO-160 acceptance criteria require three consecutive green runs. To validate:

```bash
for i in 1 2 3; do
  echo "Run $i"
  cd field-service-web && npm run test:e2e:technician
  cd ../app && mvn test -Dgroups=integration -Dtest=TechnicianJourneyIT
done
```

If any run fails and the next retry passes, the test is flaky — investigate before merging.
