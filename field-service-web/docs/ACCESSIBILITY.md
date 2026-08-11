# Accessibility Gate

WCAG 2.1 AA is a machine-enforced, build-blocking property of every release.
Passing in light and failing in dark is a defect, not a variant.

## What is checked

| Check | Tool | Runs |
|---|---|---|
| Axe-core critical/serious violations | Playwright + @axe-core/playwright | Both appearances × 4 personas |
| WCAG contrast ratios (all pairings) | Vitest contrast.test.js | Both appearances × all declared pairings |
| Greyscale survivability (chip, KPI delta, risk indicator) | Playwright greyscale.spec.js | Both appearances × 4 pages |
| Focus ring visibility (2px accent, 2px offset) | Runtime + Stylelint | Both appearances |
| Keyboard operability | Playwright keyboard.spec.js | Shell, table, modal, drawer, form |
| Reduced motion | Playwright reduced-motion.spec.js | Both appearances |
| Colour-blind-safe chart palette | seriesPalette.js + ChartTableEquivalent.jsx | Build + unit tests |

## How to run locally

```bash
cd field-service-web

# Vitest unit tests (contrast, density, greyscale helper, chart parity)
npm test

# Playwright a11y suite (axe, keyboard, greyscale, reduced motion)
npm run test:a11y

# Playwright performance suite (INP, CLS, appearance switch)
npm run test:performance

# Design-system adoption audit
npm run audit:design-system
```

The dev server must be running for Playwright tests:
```bash
npm run dev   # in a separate terminal
```

## How to interpret failures

### Axe violations (`tests/a11y/axe.spec.js`)
Each failure includes the axe rule ID and affected node targets.
Common fixes:
- `color-contrast`: update the token value or the pairing manifest minimum ratio
- `button-name`: add `aria-label` to icon-only buttons
- `image-alt`: add `alt` text to images
- `label`: associate `<label>` with form controls via `htmlFor`

### Contrast failures (`src/a11y/contrast.test.js`)
Failures include the token pairing name, actual ratio, and required minimum.
**Fix via token value change only** — never a component-local override.
Add new pairings to `src/a11y/contrastPairings.json` before using them.

### Greyscale failures (`tests/a11y/greyscale.spec.js`)
Indicates a status indicator relies on colour alone. Fix: add `aria-hidden="true"` icon element
alongside the text label. All `<Chip>` instances already comply (BR-34).

### Focus ring failures
The stylelint rule rejects `outline: none` and `outline: 0`.
To suppress for a vendor component:
1. Add a `/* stylelint-disable-next-line declaration-property-value-disallowed-list */` comment
2. Add a dated inline comment explaining the exception
3. Add an equivalent visible `:focus-visible` ring in the same rule

### Reduced motion failures (`tests/a11y/reduced-motion.spec.js`)
Any animation/transition not gated by `@media (prefers-reduced-motion: reduce)` will fail.
Wrap transitions in CSS:
```css
@media (prefers-reduced-motion: no-preference) {
  .animated { transition: opacity var(--token-duration-enter) var(--token-easing-standard); }
}
```

### Performance failures (`tests/performance/`)
- **CLS > 0.05**: images without dimensions, or layout-shifting font loads
- **INP > 200 ms**: heavy event handlers; defer with `startTransition` or debounce
- **Appearance switch > 100 ms**: check that the switch only mutates `data-appearance` on `<html>`
  (single DOM write); never trigger a stylesheet swap or component remount

### Design-system adoption failures (`scripts/audit-design-system.mjs`)
- **Adoption < 95%**: replace hard-coded values with `var(--token-*)` equivalents
- **Hard-coded literal**: add the value to `styles/token-exceptions.json` with a date and reason,
  or — preferably — replace with a token

## Persona density variants

Implemented in `src/density/personaDensity.js` (BR-35 technician-first ordering):

| Persona | Row height | Touch target | Columns (desktop) | Primary action |
|---|---|---|---|---|
| Technician | 44 px | 44 px | 1 | Bottom (thumb reach) |
| Dispatcher | 32 px | 32 px | 3 | Inline (drawer detail) |
| Operations | 56 px | 40 px | 4 | Inline (chart-forward) |
| Customer | 72 px | 48 px | 2 | Inline (plain language) |

Technician body text in light must meet 7:1 contrast (vs 4.5:1 baseline) — asserted in `src/a11y/contrast.test.js`.

## Chart accessibility

Every chart must include a `<ChartTableEquivalent>` with the same data:
```jsx
<ChartTableEquivalent
  data={series.data}
  series={series.keys}
  caption="Work Orders by State (Last 30 days)"
/>
```

The tabular equivalent is always keyboard-reachable and announced by screen readers.
Colour-blind safety is enforced by the `SERIES_PALETTE` in `src/charts/seriesPalette.js`
which uses distinct shapes alongside distinct colours.

## Phase 2 component inventory freeze

The component inventory froze at Phase 2 exit. No new components may be added to
`src/components/` without a Phase 2 exit review. Density variants override token values only —
they must not introduce new components or new visual literals.

## Focus indication rule

All interactive elements must have a visible focus ring:
- 2 px `outline` using `var(--token-border-focus)`
- 2 px `outline-offset`
- Contrast requirement: the ring must meet 3:1 contrast against adjacent colours in both appearances

The stylelint rule `declaration-property-value-disallowed-list` (outline: none/0) is the enforcement
mechanism. Exceptions require a dated comment and a visible `focus-visible` replacement.
