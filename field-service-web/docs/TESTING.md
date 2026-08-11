# Testing Guide

## Test layers

| Layer | Tool | Location | Gate |
|---|---|---|---|
| Unit tests | Vitest + jsdom | `src/**/*.test.{js,jsx}` + `scripts/**/*.test.mjs` | `npm test` |
| Component a11y (axe-core) | Playwright + @axe-core/playwright | `tests/a11y/axe.spec.js` | `npm run test:a11y` |
| Keyboard operability | Playwright | `tests/a11y/keyboard.spec.js` | `npm run test:a11y` |
| Greyscale audit | Playwright | `tests/a11y/greyscale.spec.js` | `npm run test:a11y` |
| Reduced motion | Playwright | `tests/a11y/reduced-motion.spec.js` | `npm run test:a11y` |
| Performance (INP, CLS) | Playwright | `tests/performance/vitals.spec.js` | `npm run test:performance` |
| Appearance switch timing | Playwright | `tests/performance/appearance-switch.spec.js` | `npm run test:performance` |
| Design-system adoption | Node.js script | `scripts/audit-design-system.mjs` | `npm run audit:design-system` |

## Running tests

```bash
cd field-service-web

# Unit tests (fast, no browser)
npm test

# Unit tests with coverage
npm run test:coverage

# A11y gate (needs dev server: npm run dev in separate terminal)
npm run test:a11y

# Performance gate (needs dev server)
npm run test:performance

# Design-system adoption audit
npm run audit:design-system

# Full CI pipeline (includes all gates above)
npm run build:ci
```

## Forge Shipping gate steps

The following steps are **blocking** in the Forge Shipping pipeline:

1. `npm run stylelint` — no hard-coded CSS values, no bare `outline: none`
2. `npm run lint` — ESLint including `no-hardcoded-visual-literals`
3. `npm run typecheck` — TypeScript strict mode
4. `npm run test:coverage` — Vitest with ≥ 80% coverage (lines/branches/functions/statements)
5. `npm run build` — Vite production build
6. `npm run build:catalogue` — Component catalogue smoke build
7. `npm run audit:design-system` — Token adoption ≥ 95%, bespoke ≤ 5%, zero hard-coded literals
8. `npm run test:a11y` — axe-core, keyboard, greyscale, reduced motion (both appearances)
9. `npm run test:performance` — INP ≤ 200ms, CLS ≤ 0.05, appearance switch ≤ 100ms

Steps 8 and 9 publish their reports as pipeline artifacts:
- Playwright HTML report: `playwright-report/`
- Playwright JSON results: `playwright-report/results.json`

## Unit test conventions

- Vitest tests: `*.test.js` or `*.test.jsx` inside `src/`
- Script unit tests: `*.test.mjs` inside `scripts/`
- Test helpers in `src/test/setup.js` (loaded via `setupFiles` in vite.config.js)
- Mock implementations in `src/mocks/`
- Fixtures in `src/mocks/fixtures/`

## Persona fixture files

Committed fixtures allow the full gate to run with no backend dependency:

| Fixture | Purpose |
|---|---|
| `src/mocks/fixtures/screens/dispatcher-board.json` | Dispatcher work order board |
| `src/mocks/fixtures/screens/technician-job-detail.json` | Technician job detail (360 px viewport) |
| `src/mocks/fixtures/screens/manager-dashboard.json` | Operations manager KPI dashboard |
| `src/mocks/fixtures/screens/customer-request-history.json` | Customer request history |
| `src/mocks/fixtures/charts/series.json` | Chart series data |
| `src/mocks/fixtures/charts/tabular-equivalent.json` | Expected tabular equivalent cell values |

## Contrast pairing manifest

New token pairings must be declared in `src/a11y/contrastPairings.json` before use.
An undeclared pairing fails the `contrast.test.js` audit rather than being silently skipped.

## Adding a new test

### Vitest unit test
Create `src/<module>/MyComponent.test.jsx`. Import from `vitest` and `@testing-library/react`.
The test is automatically discovered and included in coverage.

### Playwright test
Create `tests/a11y/my-check.spec.js` or `tests/performance/my-gate.spec.js`.
Import from `@playwright/test`. Run with `npx playwright test tests/<dir>`.

Add the new step to `build:ci` in `package.json` if it should block shipping.

## Performance thresholds

| Metric | Threshold | Test file |
|---|---|---|
| INP (Interaction to Next Paint) | ≤ 200 ms p95 | `tests/performance/vitals.spec.js` |
| CLS (Cumulative Layout Shift) | ≤ 0.05 | `tests/performance/vitals.spec.js` |
| Appearance switch (interaction → repaint) | ≤ 100 ms | `tests/performance/appearance-switch.spec.js` |

## Design-system adoption thresholds

| Metric | Threshold |
|---|---|
| Token adoption (% of visual declarations using `var(--token-*)`) | ≥ 95% |
| Bespoke styling (% of visual declarations not using tokens) | ≤ 5% |
| Hard-coded visual literals (colour, radius, spacing, motion outside allow-list) | 0 |

Exceptions must be listed in `styles/token-exceptions.json` with a date and rationale.
