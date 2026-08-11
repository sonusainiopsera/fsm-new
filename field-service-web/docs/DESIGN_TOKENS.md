# Design Token Reference — field-service-web

Every colour, radius, spacing, elevation and motion value in the platform is
expressed as a CSS custom property defined in this token contract. Components
MUST reference tokens via `var(--token-name)`. Hard-coded literals fail the CI
lint gate.

## Quick reference

| File | Purpose |
|------|---------|
| `src/styles/tokens.contract.css` | Vocabulary — declares all token names |
| `src/styles/tokens.light.css` | Light value set (`:root`) |
| `src/styles/tokens.dark.css` | Dark value set (`[data-appearance="dark"]`) |
| `src/styles/base.css` | CSS reset + typography defaults + numeric utility |
| `src/styles/index.css` | Aggregator — import this from `src/main.jsx` |
| `src/styles/tokens.contract.json` | Machine-readable token list (generated) |

---

## Typography

| Token | Value | Usage |
|-------|-------|-------|
| `--fs-xs` | 12 px | Caption, badge label, helper text |
| `--fs-sm` | 13 px | Secondary body, compact table cell |
| `--fs-md` | 14 px | Primary body copy, form controls |
| `--fs-base` | 16 px | Section heading, card title |
| `--fs-lg` | 20 px | Page sub-heading (use `--ls-tight`) |
| `--fs-xl` | 24 px | Page heading (use `--ls-tight`) |
| `--fs-2xl` | 30 px | Hero figure, KPI value (use `--ls-tight`) |
| `--fw-regular` | 400 | Body copy |
| `--fw-medium` | 500 | Emphasis within body |
| `--fw-semibold` | 600 | Headings, labels |
| `--fw-bold` | 700 | KPI figures, alert headings |
| `--lh-tight` | 1.25 | Headings, badges |
| `--lh-body` | 1.5 | Default body line-height |
| `--lh-relaxed` | 1.625 | Long-form help text |
| `--ls-tight` | −0.015 em | Required at `--fs-lg` (20 px) and above |
| `--ls-normal` | 0 em | Default tracking |
| `--font-sans` | System stack | Fastest first paint, no layout shift |
| `--font-mono` | Monospace stack | Code snippets, IDs |
| `--font-brand` | ⚠️ **Q13 PLACEHOLDER** | Replace after brand ratification |
| `--numeric-figures` | `tabular-nums` | Column-aligned numbers |

### Tabular figures mandate

Every numeric cell, KPI value, countdown and score MUST use the `.numeric`
utility class or `font-variant-numeric: var(--numeric-figures)` directly.
Column misalignment due to proportional figures is a regression.

### Tightened letter-spacing rule

Any text element rendered at `--fs-lg` (20 px) or above MUST also apply
`letter-spacing: var(--ls-tight)`. Omitting it at large sizes makes display
copy look too loose.

---

## Spacing

Base unit: **4 px**. Steps follow a 4 px rhythm.

| Token | Value | Usage |
|-------|-------|-------|
| `--space-1` | 4 px | Minimal gap (icon + label) |
| `--space-2` | 8 px | Compact internal padding |
| `--space-3` | 12 px | Form field internal padding |
| `--space-4` | 16 px | Standard section padding |
| `--space-6` | 24 px | Card padding, section gap |
| `--space-8` | 32 px | Large section separation |
| `--content-max-width` | 1440 px | Maximum content column width |
| `--gutter` | 24 px | Horizontal page margin |

---

## Radius

| Token | Value | Component type |
|-------|-------|---------------|
| `--radius-control` | 6 px | Inputs, buttons, badges, chips |
| `--radius-card` | 10 px | Cards, panels, table containers |
| `--radius-overlay` | 14 px | Modals, drawers, tooltips |
| `--radius-pill` | 9999 px | Avatars, full-round chips |

### No-mixed-radii rule

Do not mix radius tokens within a single component. A card with `--radius-card`
on the container must use `--radius-control` (or `0`) on inner elements —
never `--radius-overlay`. Mixing radii signals that components from different
layers are combined incorrectly.

---

## Elevation

**At most two elevation levels system-wide.** Structural separation is carried
by `--color-border` (a 1 px hairline), not by shadow depth.

| Token | Usage |
|-------|-------|
| `--elevation-1` | Cards, inline dropdowns |
| `--elevation-2` | Modals, drawer sheets, popovers |
| `--color-border` | Hairline border for structural separation |
| `--scrim` | Overlay backdrop behind modals and drawers |

### Dark mode elevation

In dark mode, `--elevation-1` and `--elevation-2` carry reduced shadow values.
Surface separation relies on `--color-border` and distinct `--color-surface-*`
background tokens. Never introduce a third shadow level; use `--color-border`
instead.

---

## Motion

**Nothing exceeds 300 ms.** Exactly one easing curve is used platform-wide.

| Token | Value | Usage |
|-------|-------|-------|
| `--duration-micro` | 120 ms | Hover state, focus ring, toggle |
| `--duration-entry` | 180 ms | Element entering the viewport |
| `--duration-overlay` | 240 ms | Panel, drawer, modal open/close |
| `--easing-standard` | `cubic-bezier(0.4, 0, 0.2, 1)` | All transitions |

### Reduced motion

The `base.css` file includes a `@media (prefers-reduced-motion: reduce)` block
that overrides all animation and transition durations to `0.01 ms`. No layout
or information changes result — only the transition animation is removed.

---

## Colour

### Neutral ramp

12-step enterprise greyscale from `--color-neutral-0` (white) to
`--color-neutral-950` (near-black graphite). **Use semantic tokens in
components** — never reference raw neutral steps directly in component CSS.

### Surface & Text tokens

These tokens invert between light and dark appearances. Always use them.

| Token | Light | Dark |
|-------|-------|------|
| `--color-surface-base` | `#ffffff` | `#101214` |
| `--color-surface-raised` | `#f8f9fa` | `#1a1d20` |
| `--color-surface-overlay` | `#ffffff` | `#212529` |
| `--color-text-primary` | `#212529` | `#f1f3f5` |
| `--color-text-secondary` | `#868e96` | `#868e96` |
| `--color-text-disabled` | `#adb5bd` | `#495057` |
| `--color-text-on-accent` | `#ffffff` | `#ffffff` |

### Accent family ⚠️ Q13 PLACEHOLDER

The accent hue is a **placeholder** pending brand ratification (open
question Q13). Ratification requires only a token value change in
`tokens.light.css` and `tokens.dark.css` — no component rebuild.

| Token | Purpose |
|-------|---------|
| `--color-accent-subtle` | Very light tint — hover backgrounds |
| `--color-accent-muted` | Mid tone — selected state |
| `--color-accent-base` | Primary interactive colour |
| `--color-accent-emphasis` | Hover / pressed state |
| `--color-accent-text` | Text on accent-coloured background |

### Semantic families (exactly four)

| Family | Tokens | Usage |
|--------|--------|-------|
| **info** | `--color-info-{subtle,muted,base,emphasis,text}` | Informational banners, notices |
| **success** | `--color-success-{…}` | Confirmation, completed state |
| **warning** | `--color-warning-{…}` | At-risk SLA, caution states |
| **danger** | `--color-danger-{…}` | Error, breached SLA, destructive actions |

No fifth semantic family may be added without updating `tokens.contract.css`,
both value sets, the parity test, and this document.

---

## Q13 Placeholder ratification procedure

The following tokens carry `Q13 PLACEHOLDER` comments in the contract:

- `--font-brand` — licensed typeface family
- `--color-accent-*` — brand accent hue

**To ratify:**
1. Update values in `src/styles/tokens.light.css` and `src/styles/tokens.dark.css`.
2. Remove the `/* Q13 PLACEHOLDER */` comment from `tokens.contract.css`.
3. Run `npm run generate-tokens` to regenerate `tokens.contract.json`.
4. Run `npm test` to confirm parity.
5. Update this document's colour table above.
6. No component files need editing — all components already reference the token.

---

## Lint enforcement

### Stylelint

`stylelint.config-standard` plus a `declaration-property-value-allowed-list`
rule restricts colour, radius, spacing, shadow and duration properties to
`var(--token)` references. The token definition files are excluded from
linting. Run: `npm run stylelint`.

### Custom ESLint rule (`no-hardcoded-visual-literals`)

Loaded via `--rulesdir eslint-local-rules`. Rejects literal hex, rgb/hsl
colours, bare `px` radius and spacing values, and bare `ms/s` durations in JSX
`style` props. Run: `npm run lint`.

### Allow-list for exceptions

`src/styles/token-exceptions.json` records justified exceptions. Every entry
requires a `reason` and a `date`. Exceptions MUST be scoped to a specific file
path and reviewed at each release.

---

## Build

```sh
# Install dependencies
npm install

# Generate token contract JSON
npm run generate-tokens

# Run all checks then bundle
npm run build:node

# Development server
npm run dev
```

Coverage threshold: **80 %** (lines, functions, branches, statements).
