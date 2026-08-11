# Design Token Reference

This document enumerates every token in the field-service design system, its intended usage, and the rules that enforce correct adoption.

## Contents

1. [Token vocabulary](#token-vocabulary)
2. [Typography](#typography)
3. [Spacing](#spacing)
4. [Radius](#radius)
5. [Elevation — two-level rule](#elevation--two-level-rule)
6. [Motion](#motion)
7. [Colour](#colour)
8. [Lint enforcement](#lint-enforcement)
9. [Q13 placeholder ratification procedure](#q13-placeholder-ratification-procedure)

---

## Token vocabulary

All tokens are named `--fs-<category>-<scale>` and are declared as CSS custom properties.
Light values are applied to `:root`; dark values override via `html[data-appearance="dark"]`.

The machine-readable vocabulary lives in `src/styles/tokens.contract.json` and is generated from `src/styles/tokens.contract.css` by `scripts/generate-token-contract.mjs`.

---

## Typography

| Token | Light value | Dark value | Intended use |
|---|---|---|---|
| `--fs-text-2xs` | `0.75rem` | same | 12 px — labels, helper text |
| `--fs-text-xs` | `0.8125rem` | same | 13 px — captions, badges |
| `--fs-text-sm` | `0.875rem` | same | 14 px — body secondary |
| `--fs-text-base` | `1rem` | same | 16 px — body primary |
| `--fs-text-lg` | `1.25rem` | same | 20 px — subheadings |
| `--fs-text-xl` | `1.5rem` | same | 24 px — headings |
| `--fs-text-2xl` | `1.875rem` | same | 30 px — page titles |
| `--fs-line-height-body` | `1.5` | same | Body text (WCAG 1.4.8) |
| `--fs-tracking-tight` | `-0.015em` | same | Letter-spacing for sizes ≥ 20 px |
| `--fs-font-variant-numeric` | `tabular-nums` | same | **Always apply to numeric cells, KPIs, scores, countdowns** via the `.numeric` utility class |
| `--fs-font-family` | system-ui stack | same | **[Q13]** Swap for licensed typeface when brand is ratified |

### Tabular figures mandate

Every numeric cell, KPI value, countdown and score **must** use the `.numeric` CSS class or `font-variant-numeric: var(--fs-font-variant-numeric)`. This ensures columnar alignment at all widths including very long values (e.g. large currencies). Omitting it is a layout regression.

---

## Spacing

Base unit: **4 px**. Rhythm steps match multiples of the base.

| Token | Value | Intended use |
|---|---|---|
| `--fs-space-1` | `0.25rem` | 4 px — icon internal padding |
| `--fs-space-2` | `0.5rem` | 8 px — button padding, badge padding |
| `--fs-space-3` | `0.75rem` | 12 px — tight stacking |
| `--fs-space-4` | `1rem` | 16 px — standard component padding |
| `--fs-space-6` | `1.5rem` | 24 px — section internal spacing |
| `--fs-space-8` | `2rem` | 32 px — section-to-section rhythm |
| `--fs-content-max` | `1440px` | Page content cap |
| `--fs-gutter` | `1.5rem` | Side gutters inside the content container |

---

## Radius

| Token | Value | Intended use |
|---|---|---|
| `--fs-radius-control` | `6px` | Inputs, buttons, tags, chips |
| `--fs-radius-card` | `10px` | Cards, panels, section containers |
| `--fs-radius-overlay` | `14px` | Modals, drawers, popovers |
| `--fs-radius-pill` | `9999px` | Avatar circles, progress pills |

**No-mixed-radii rule:** components that contain nested elements (e.g. a card with buttons) must use consistent radii from the same tier. A `--fs-radius-overlay` container must not hold children with `--fs-radius-card` corners pointing outward — this creates a visual mismatch. Nest one tier smaller: overlay → card inside, card → control inside.

---

## Elevation — two-level rule

There are **exactly two elevation levels** system-wide. Structural depth is achieved through the hairline border token, not shadow stacking.

| Token | Light value | Dark value | Intended use |
|---|---|---|---|
| `--fs-shadow-1` | subtle 1 px shadow | stronger shadow | Raised elements above base surface (cards, inputs on focus) |
| `--fs-shadow-2` | 4 px layered shadow | deep shadow | Overlapping elements (modals, dropdowns, popovers) |
| `--fs-border-hairline` | `1px solid #e2e8f0` | `1px solid #334155` | **Preferred structural separator.** Use this instead of shadow whenever possible. |
| `--fs-scrim` | `rgba(0,0,0,0.40)` | `rgba(0,0,0,0.65)` | Modal/drawer backdrop overlay |

**Two-elevation rule:** no component may create a third elevation level by nesting `--fs-shadow-2` inside a `--fs-shadow-2` container. If you need visual depth, use `--fs-border-hairline`.

**Box-shadow lint gate:** the Stylelint `declaration-property-value-allowed-list` rule rejects any `box-shadow` value that is not a `var(--fs-shadow-*)` reference or `none`. This is enforced in CI.

---

## Motion

| Token | Value | Intended use |
|---|---|---|
| `--fs-duration-micro` | `120ms` | Icon swap, tooltip reveal, checkbox tick |
| `--fs-duration-entry` | `180ms` | Panel slide-in, dropdown open |
| `--fs-duration-overlay` | `240ms` | Modal open/close, drawer |
| `--fs-easing-standard` | `cubic-bezier(0.4, 0, 0.2, 1)` | All transitions and animations |

**Ceiling rule:** no token may exceed **300 ms**. Enforced by `tokens.contract.test.js`.

**Reduced-motion:** `base.css` contains a `@media (prefers-reduced-motion: reduce)` block that overrides all durations to `0.01ms` — users with OS-level reduced-motion see no animation without any layout or information change.

---

## Colour

### Neutral ramp

Fixed absolute greyscale from `--fs-neutral-0` (#ffffff) to `--fs-neutral-950` (#020617). The ramp is the same in both appearances; semantic surface tokens pick different ramp values per appearance.

### Semantic surface / text

| Token | Light | Dark | Intended use |
|---|---|---|---|
| `--fs-color-surface` | `#ffffff` | `#11131a` | Page / base background |
| `--fs-color-surface-raised` | `#f8fafc` | `#1c2033` | Card / panel (one elevation above surface) |
| `--fs-color-text-primary` | `#0f172a` | `#f1f5f9` | Body text |
| `--fs-color-text-secondary` | `#475569` | `#94a3b8` | Helper text, captions |
| `--fs-color-text-disabled` | `#94a3b8` | `#475569` | Disabled state text |
| `--fs-color-border` | `#e2e8f0` | `#334155` | Default border |

### Accent family — [Q13 PLACEHOLDER]

The accent hue is a **placeholder blue** pending brand ratification (open question Q13). Do not build brand-identity surfaces on accent tokens until ratification is complete.

| Token | Light | Dark | Intended use |
|---|---|---|---|
| `--fs-accent-subtle` | `#eff6ff` | `#1e3a5f` | Accent background tint |
| `--fs-accent-muted` | `#bfdbfe` | `#1d4ed8` | Muted accent fill |
| `--fs-accent-default` | `#3b82f6` | `#60a5fa` | Primary interactive accent |
| `--fs-accent-strong` | `#1d4ed8` | `#93c5fd` | Pressed / active state |
| `--fs-accent-on` | `#ffffff` | `#0f172a` | Foreground on accent backgrounds |

### Semantic families

Exactly **four** semantic families; a fifth is forbidden (enforced by test).

| Family | Tokens | Usage |
|---|---|---|
| **informational** | `--fs-info-{bg,border,text,icon}` | Info alerts, help panels |
| **success** | `--fs-success-{bg,border,text,icon}` | Completed states, confirmations |
| **warning** | `--fs-warning-{bg,border,text,icon}` | At-risk SLA, soft warnings |
| **danger** | `--fs-danger-{bg,border,text,icon}` | Errors, hard violations, SLA breaches |

---

## Lint enforcement

### Stylelint (CSS files)

`declaration-property-value-allowed-list` restricts the following properties to `var(--fs-*)` references: `color`, `background-color`, `border-color`, `outline-color`, `border-radius`, `padding`, `margin`, `gap`, `box-shadow`, `transition-duration`, `animation-duration`.

**Exceptions** are documented in `src/styles/token-exceptions.json` with `path`, `reason`, and `date` fields. Exceptions must be path-scoped (e.g. vendor file paths) — no global exceptions.

### ESLint custom rule (JSX files)

`eslint-local-rules/no-hardcoded-visual-literals.js` loaded via `eslint --rulesdir eslint-local-rules` rejects literal hex colours, `rgb()`/`hsl()` values, `Npx` radius/spacing, and `Nms` duration values in JSX `style` prop objects.

### Build gate

`npm run build:node` runs in order: `stylelint → eslint → typecheck → test:coverage → vite build`. All lint and test steps are blocking. A single violation fails the build.

---

## Q13 placeholder ratification procedure

When the brand accent hue and licensed typeface are confirmed (question Q13):

1. Update `--fs-font-family` in both `tokens.light.css` and `tokens.dark.css` with the licensed typeface name and preload strategy.
2. Update all `--fs-accent-*` tokens in both files with the ratified hue values.
3. Run `npm run generate-tokens` to regenerate `tokens.contract.json`.
4. Run `npm test` — the parity test confirms both value sets are complete.
5. Remove the `[Q13]` markers from `tokens.contract.css` comments.
6. File a PR titled "Brand ratification: accent hue + typeface (Q13)".

No component code changes are required — the token values are the only diff.
