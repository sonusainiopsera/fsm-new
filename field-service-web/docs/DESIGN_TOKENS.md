# Design Token Reference

This document is the authoritative usage guide for the field-service-web design token system.
Every colour, spacing, radius, elevation, motion and typography decision must be expressed as a `var(--token-*)` reference — never as a hard-coded value.

---

## Quick reference

| Token | Value (light) | Value (dark) | Use |
|---|---|---|---|
| `--token-surface-page` | `#f9fafb` | `#111827` | Page background |
| `--token-surface-card` | `#ffffff` | `#1f2937` | Card / panel background |
| `--token-text-primary` | `#111827` | `#f9fafb` | Body text, headings |
| `--token-text-secondary` | `#6b7280` | `#9ca3af` | Captions, metadata |
| `--token-accent-500` | `#3b82f6` | `#60a5fa` | Primary interactive colour |
| `--token-danger-default` | `#ef4444` | `#f87171` | Error, destructive action |
| `--token-border-default` | `#e5e7eb` | `#374151` | Hairline borders, dividers |
| `--token-duration-enter` | `180ms` | `180ms` | Element entry animation |

---

## How the token system works

```
tokens.contract.css   ← vocabulary (names only, no values)
  ↓  imported by
tokens.light.css      ← binds all tokens at :root  (default appearance)
tokens.dark.css       ← overrides all tokens at [data-appearance="dark"]
base.css              ← reset + utilities (consumes var(--token-*) only)
index.css             ← aggregator — import this file only
```

The `data-appearance` attribute is set on `<html>` before first paint by the inline script in `index.html`. No runtime style injection occurs; all CSS is static and loaded from a content-hashed asset, satisfying `Content-Security-Policy: default-src 'self'` with no `unsafe-inline`.

---

## Typography tokens

| Token | Value | Notes |
|---|---|---|
| `--token-fs-12` | `0.75rem` | Small labels, legal |
| `--token-fs-13` | `0.8125rem` | Tight UI labels |
| `--token-fs-14` | `0.875rem` | Secondary body |
| `--token-fs-16` | `1rem` | Body text (default) |
| `--token-fs-20` | `1.25rem` | Section headings (tightened tracking) |
| `--token-fs-24` | `1.5rem` | Page headings (tightened tracking) |
| `--token-fs-30` | `1.875rem` | Hero, KPI values (tightened tracking) |
| `--token-lh-body` | `1.5` | Body line-height |
| `--token-ls-base` | `0em` | Default letter-spacing |
| `--token-ls-tight` | `-0.02em` | Applied at `--token-fs-20` and above |
| `--token-family-base` | system font stack | Never blocks first paint |
| `--token-family-brand` | ⚠️ **Q13 PLACEHOLDER** | Swap after brand typeface ratified |
| `--token-numeric` | `tabular-nums` | Apply to every numeric cell, KPI, countdown, score |

### Tightened tracking rule

Any element sized `--token-fs-20` or larger **must** apply `letter-spacing: var(--token-ls-tight)`. `base.css` applies this automatically to `h1`, `h2`, `h3`.

### Tabular figures mandate

Every numeric value that participates in a column, a comparison, or a live counter **must** apply the `.numeric` utility class (or set `font-variant-numeric: var(--token-numeric)` directly). This includes:

- Work order counts, KPI values, SLA countdowns
- Stock quantities, cost figures, technician scores
- Any table column that contains numbers

Failure to apply tabular figures causes column misalignment as digit widths vary.

---

## Spacing tokens

Base rhythm: **4 px**.

| Token | Value | Typical use |
|---|---|---|
| `--token-space-1` | `4px` | Icon gap, tight inline padding |
| `--token-space-2` | `8px` | Small internal padding, badge padding |
| `--token-space-3` | `12px` | Form field internal padding |
| `--token-space-4` | `16px` | Card internal padding, list item padding |
| `--token-space-6` | `24px` | Section gap, large card padding |
| `--token-space-8` | `32px` | Page section separation |
| `--token-content-max-width` | `1440px` | Max width for `.content-container` |
| `--token-gutter` | `24px` | Page horizontal padding |

---

## Radius tokens

| Token | Value | Use |
|---|---|---|
| `--token-radius-control` | `6px` | Inputs, buttons, select, tags, chips |
| `--token-radius-card` | `10px` | Cards, panels, table containers |
| `--token-radius-overlay` | `14px` | Modals, drawers, popovers, tooltips |
| `--token-radius-pill` | `9999px` | Avatar images, status badges |

### No-mixed-radii rule

A single component must not mix radius tokens from different tiers. A card component uses `--token-radius-card` on its container and `--token-radius-control` on controls inside it — not `--token-radius-overlay` on either. If an element is enclosed in a card, its radius should be ≤ the card's radius.

---

## Elevation tokens

The platform uses **at most two elevation levels**. Structural separation is expressed by the **hairline border token**, not by adding shadow depth.

| Token | Value (light) | Use |
|---|---|---|
| `--token-elevation-1` | subtle shadow | Cards, inline dropdowns |
| `--token-elevation-2` | elevated shadow | Modals, drawers |
| `--token-elevation-border` | `1px solid #e5e7eb` | Panels, table rows, dividers |
| `--token-elevation-scrim` | `rgba(0,0,0,0.5)` | Modal / drawer backdrop |

### Two-elevation rule

Never introduce a third elevation level. If a component needs more visual separation, use `--token-elevation-border` to add a hairline border — do not add a custom box-shadow. The Stylelint rule will fail the build on any `box-shadow` literal outside the two elevation tokens.

---

## Motion tokens

| Token | Value | Use |
|---|---|---|
| `--token-duration-micro` | `120ms` | Toggles, checkboxes, icon swaps |
| `--token-duration-enter` | `180ms` | Page elements entering the viewport |
| `--token-duration-overlay` | `240ms` | Modals, drawers, sheets opening |
| `--token-easing-standard` | `cubic-bezier(0.4, 0, 0.2, 1)` | All transitions |

**Ceiling:** No motion token may exceed **300 ms**. A unit test enforces this.

**Reduced motion:** `base.css` applies `transition-duration: 0.01ms` and `animation-duration: 0.01ms` when `prefers-reduced-motion: reduce` is detected. Layout and information are preserved; only visual motion is suppressed.

---

## Colour tokens

### Neutral greyscale ramp

`--token-neutral-0` (white) through `--token-neutral-950` (near-black). Use semantic tokens for text, surfaces and borders — reference the ramp directly only when a non-semantic shade is genuinely required (e.g., an illustration fill).

### Accent family (Q13 PLACEHOLDER)

`--token-accent-50` through `--token-accent-900`. The placeholder value is a neutral enterprise blue. **All ten accent tokens must be replaced when the brand hue is ratified.** See [Q13 Ratification Procedure](#q13-ratification-procedure) below.

### Semantic families (exactly four)

| Family | Subtle (bg) | Default (fg/icon) | Emphasis (text on dark) |
|---|---|---|---|
| `info` | `--token-info-subtle` | `--token-info-default` | `--token-info-emphasis` |
| `success` | `--token-success-subtle` | `--token-success-default` | `--token-success-emphasis` |
| `warning` | `--token-warning-subtle` | `--token-warning-default` | `--token-warning-emphasis` |
| `danger` | `--token-danger-subtle` | `--token-danger-default` | `--token-danger-emphasis` |

A unit test fails the build if a fifth semantic family is introduced.

### Surface, text and border tokens

| Token | Light | Dark |
|---|---|---|
| `--token-surface-page` | `#f9fafb` | `#111827` |
| `--token-surface-card` | `#ffffff` | `#1f2937` |
| `--token-surface-elevated` | `#ffffff` | `#374151` |
| `--token-surface-overlay` | `rgba(255,255,255,0.95)` | `rgba(0,0,0,0.8)` |
| `--token-text-primary` | `#111827` | `#f9fafb` |
| `--token-text-secondary` | `#6b7280` | `#9ca3af` |
| `--token-text-disabled` | `#9ca3af` | `#6b7280` |
| `--token-text-on-accent` | `#ffffff` | `#ffffff` |
| `--token-border-default` | `#e5e7eb` | `#374151` |
| `--token-border-focus` | `#3b82f6` | `#60a5fa` |

---

## Lint enforcement

The build fails on any hard-coded visual literal. Two gates:

1. **Stylelint** (`npm run stylelint`) — rejects hex, rgb, hsl, named colours, bare px radii, bare px spacings, bare ms durations, and box-shadow literals in `.css` files outside the token definition files.

2. **ESLint custom rule** (`local-rules/no-hardcoded-visual-literals`) — rejects the same literal types in JSX `style` props.

### Allow-list exceptions

Legitimate exceptions are documented in `styles/token-exceptions.json`:

```json
{
  "exceptions": [
    {
      "file": "src/vendor/third-party-component.css",
      "property": "color",
      "value": "#1a73e8",
      "reason": "Google Maps SDK injects this colour; cannot be overridden via token.",
      "date": "2026-09-01"
    }
  ]
}
```

Rules:
- File path must be specific — never a wildcard.
- Reason must be non-trivial (not "convenience").
- Date must be in `YYYY-MM-DD` format.
- A unit test validates all entries; invalid entries fail the build.

---

## Q13 Ratification Procedure

Q13 covers: accent hue, brand typeface, and logo lockup. These are marked `Q13 PLACEHOLDER` in `tokens.contract.css` and in this document.

To ratify:

1. Update the 10 `--token-accent-*` values in `src/styles/tokens.light.css` and `src/styles/tokens.dark.css`.
2. Update `--token-family-brand` in both value-set files. Add the subset-and-preload `<link>` tags to `index.html`.
3. Remove the `Q13 PLACEHOLDER` comments from the affected token entries in `tokens.contract.css`.
4. Run `npm run test` to verify parity. Run `npm run build:ci` to verify no lint regressions.
5. Record the ratification date in `DESIGN_TOKENS.md` and commit.

No code changes are required outside the two value-set files and `index.html`.
