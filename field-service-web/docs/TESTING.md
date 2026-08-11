# Testing Guide

## Test layers

The test suite is structured in two layers:

### Layer 1 — Unit / component tests (Vitest + jsdom)

Run with `npm test` (or `vitest run`).  No browser, no running dev server.

| Test file | Covers |
|-----------|--------|
| `src/density/__tests__/personaDensity.test.js` | All four persona density presets: resolved row height, touch-target min, column count at target viewport, primary-action placement |
| `src/a11y/__tests__/contrast.test.js` | Contrast ratio calculation, pairing manifest validation (both appearances), greyscale survivability DOM helper |
| `src/a11y/__tests__/adoptionMetrics.test.js` | Adoption-audit metric calculation: token count, bespoke %, hardcoded literal detection |
| `src/charts/__tests__/ChartTableEquivalent.test.jsx` | Chart table structure, value parity with fixture, empty state, keyboard accessibility |
| `src/appearance/__tests__/` | Appearance mirror, resolver, provider |
| `src/app/__tests__/` | Navigation derivation, error boundary, sidebar collapse, network status |
| `src/components/__tests__/` | All primitive components |

### Layer 2 — E2E / screen tests (Playwright)

Run with `npm run test:a11y` or `npm run test:performance`.  Requires:
1. `npm install` (installs `@playwright/test`, `@axe-core/playwright`)
2. `npx playwright install chromium`
3. Dev server running (`npm run dev` in a separate terminal)

| Test file | Covers |
|-----------|--------|
| `tests/a11y/axe.spec.js` | WCAG 2.1 AA via axe-core in both appearances for every persona surface |
| `tests/a11y/keyboard.spec.js` | Full keyboard traversal: shell nav, table sort/select, modal, drawer, form |
| `tests/a11y/greyscale.spec.js` | Greyscale survivability: every Chip/KPI delta/risk indicator retains text + icon |
| `tests/a11y/reduced-motion.spec.js` | Zero non-zero durations under prefers-reduced-motion; state changes remain visible |
| `tests/performance/vitals.spec.js` | CLS ≤ 0.05 and INP ≤ 200ms p95 per persona screen |
| `tests/performance/appearance-switch.spec.js` | Appearance switch ≤ 100ms, no reload, no wrong-appearance frame |

## Pipeline integration

The blocking Forge Shipping steps are:

```yaml
# CI pseudo-config (adapt to your pipeline runner)
steps:
  - name: Unit tests + coverage
    run: npm run test:coverage

  - name: Accessibility gate         # BLOCKING
    run: npm run test:a11y
    artifact: test-results/playwright-html/

  - name: Performance gate           # BLOCKING
    run: npm run test:performance
    artifact: test-results/playwright-html/

  - name: Design-system adoption     # BLOCKING
    run: npm run audit:design-system
```

A violation in any blocking step prevents the release from proceeding.

## Adding new contrast pairings

Any new foreground/background combination used in CSS modules must be declared in `src/a11y/contrastPairings.json`:

```json
{
  "id": "my-new-pairing",
  "fgToken": "--color-text-primary",
  "bgToken": "--color-surface-overlay",
  "fg": { "light": "#212529", "dark": "#f1f3f5" },
  "bg": { "light": "#ffffff", "dark": "#212529" },
  "minRatio": 4.5,
  "personas": ["dispatcher", "manager"]
}
```

The contrast unit test (`src/a11y/__tests__/contrast.test.js`) verifies all declared pairings pass in both appearances.

## Interpreting failures

### axe-core violation

```
Axe violations [technician / dark]:
  color-contrast: Elements must meet minimum color contrast ratio thresholds
    <span class="chip chip-state-new">New</span>
```

Fix: change the token values in `tokens.dark.css` — never override locally.

### Design-system adoption failure

```
FAIL  Adoption 92.3% < required 95%
FAIL  1 hardcoded literal(s) found:
  Button/Button.module.css:15  →  "#e0f2fe"
```

Fix: replace the hardcoded value with the appropriate `var(--token)` reference.  If no suitable token exists, add one to `tokens.contract.css`, `tokens.light.css`, and `tokens.dark.css`.

### Reduced-motion failure

```
Non-zero animation/transition durations under reduced-motion:
  <div class="sidebar"> trans=240ms
```

Fix: ensure all transition durations reference `var(--duration-*)` tokens.  The `base.css` `@media (prefers-reduced-motion: reduce)` block zeroes all `--duration-*` tokens, which cascades to all components using them.
