# Accessibility Gate

This document describes the automated accessibility gate that is a blocking step for every release of the field-service-web frontend.  Accessibility is a build-blocking property of the system — not a review opinion.

## Overview

Every release must pass all of the following checks:

| Check | Tool | Threshold | Pipeline step |
|-------|------|-----------|---------------|
| WCAG 2.1 AA (both appearances) | axe-core via Playwright | 0 critical/serious violations | `test:a11y` |
| Greyscale survivability | Playwright CSS filter | Every status indicator: text + icon | `test:a11y` |
| Focus visibility | Runtime Playwright | 2px accent ring, 2px offset | `test:a11y` |
| Keyboard operability | Playwright | Shell, table, modal, drawer, form | `test:a11y` |
| Reduced-motion | Playwright reducedMotion | 0 non-zero durations | `test:a11y` |
| Contrast (both appearances) | `src/a11y/contrast.js` | 4.5:1 (7:1 technician light) | Vitest |
| Design-system adoption | `scripts/audit-design-system.mjs` | ≥95% adoption, ≤5% bespoke, 0 literals | `audit:design-system` |

## Running Locally

```bash
# Unit + component tests (contrast, density, chart table, adoption metrics)
npm test

# Accessibility E2E tests (requires npm install && npm run dev in another terminal)
npm run test:a11y

# Performance gates
npm run test:performance

# Design-system adoption audit
npm run audit:design-system
```

## WCAG 2.1 AA — Dual-appearance Rule

A violation present in only one appearance is a defect and fails the build.  The parameterised axe-core suite (see `tests/a11y/axe.spec.js`) runs every test twice: once with `data-appearance="light"` and once with `data-appearance="dark"`.

**Mitigation for risk R18:** Two appearances double the visual and accessibility defect surface.  The dual-appearance gate ensures both surfaces are verified at every release.

## Contrast Requirements

| Persona | Minimum contrast | Applies to |
|---------|-----------------|------------|
| All (light) | 4.5:1 | All text and non-decorative elements |
| All (dark) | 4.5:1 | All text and non-decorative elements |
| Technician (light) | **7:1** | Body text (`--color-text-primary` on `--color-surface-base`) |

Token pairings are declared in `src/a11y/contrastPairings.json`.  Any new pairing must be added to this manifest — the validator fails on undeclared pairings rather than silently skipping them.

## Greyscale Survivability (BR-34)

Every status indicator (Chip, KPI delta, risk indicator, chart series) must convey meaning through **colour + text + a distinct icon or shape**.  Colour alone is never sufficient.

Implementation:
- `Chip`: carries `data-shape` attribute on the icon span and a visible text label
- `KpiCard`: delta signs (+/-) are textual
- Chart series: each series has a distinct `markerShape` and `strokeDasharray` in `src/charts/seriesPalette.js`

## Focus Visibility

The Stylelint rule `declaration-property-value-disallowed-list` rejects `outline: none` and `outline: 0` in all CSS modules.  Any suppression of the native focus ring must be replaced with a visible equivalent in the same rule.

Focus ring specification:
- Width: 2px
- Offset: 2px
- Colour: `var(--color-accent-base)` (passes 3:1 contrast against both surfaces)

## Persona Density Variants

Four persona presets drive layout tokens for each role surface.  Presets are defined in `src/density/personaDensity.js` in field-first order (technician first, BR-35).

| Persona | Density | Row height | Touch target | Columns (1440px) |
|---------|---------|-----------|--------------|-----------------|
| Technician | comfortable | 56px | 44px | 1 |
| Dispatcher | compact | 32px | 32px | 4 |
| Manager | comfortable | 56px | 40px | 3 |
| Customer | comfortable | 72px | 44px | 2 |

## Reduced-motion

When `prefers-reduced-motion: reduce` is active, all CSS `transition-duration` and `animation-duration` properties are set to `0.01ms` or `0ms` by the base stylesheet (`src/styles/base.css`).  The reduced-motion Playwright suite verifies zero non-zero durations remain.

No state change, alert or transition may become imperceptible when motion is disabled.

## Phase 2 Component-inventory Freeze

This WO closes Phase 1.  At Phase 2 exit, the component inventory is frozen.  No new components may be added without a documented design review.  The design-system adoption audit enforces this by failing at 95% threshold — new bespoke components would reduce adoption and trigger a build failure.
