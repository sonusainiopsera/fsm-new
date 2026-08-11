# Component Primitive Library — Frozen Phase 2 Inventory

This document defines the shared component primitives for `field-service-web`. The inventory is **frozen at Phase 2**: no new primitives may be added and no existing primitive may be deleted or renamed without an architecture decision record (ADR). Every feature epic must compose from these primitives.

---

## Governance

- All styling must use `var(--token-*)` exclusively. Hard-coded visual literals (hex colors, pixel font sizes, raw spacing values) are rejected by the `no-hardcoded-visual-literals` ESLint rule.
- CSS Modules are the preferred style co-location mechanism. Inline styles using `var(--token-*)` references are acceptable when a module file would add no value.
- The `DensityContext` (`comfortable` | `compact`) must be respected by any primitive that displays repeated rows or compact-able content.
- All interactive primitives must meet WCAG 2.1 AA: keyboard navigable, labelled, focus visible.
- `ScorePresentation` must never use semantic color (BR-33). `Chip` must convey meaning via icon + text + color (BR-34).

---

## Primitives

### `Button`

Five variants: `primary`, `secondary`, `tertiary`, `ghost`, `destructive`. Unknown variant emits a console warning. `touchTarget` prop enforces 44 px minimum touch area. Default type is `button`.

### `PageHeader`

Breadcrumb `<nav>` + `<h1>` title + optional `primaryAction` (exactly one allowed — throws if more than one passed) + `secondaryActions[]`. Primary action renders as `variant="primary"` Button; secondary as `variant="ghost"`.

### `KpiCard`

13 px muted label, 30 px tabular-nums value, optional unit suffix. Delta chip omitted when `delta` is `null` or `undefined`. Target attainment progress bar (4 px neutral track) rendered when `target` and `currentRaw` are provided. Optional SVG sparkline via `sparkline[]` data.

### `DataTable`

Headless column definitions (`{ key, header, sortable?, render? }`). Respects `DensityContext` for row height (40 px comfortable / 32 px compact). ResizeObserver-based responsive stacking below 768 px viewport width. Renders `EmptyState` when `rows` is empty. `DensityToggle` exported separately for placement in toolbar.

### `DetailDrawer`

Side panel with focus trap (Tab cycle), Escape-to-close, and scrim click-to-close. `role="dialog" aria-modal="true" aria-labelledby="drawer-title"`. Restores focus to trigger element on close.

### `Modal`

Centred overlay with same focus trap and Escape/scrim close as `DetailDrawer`. `role="dialog" aria-modal="true" aria-labelledby="modal-title"`. Three sizes: `sm` (400 px), `md` (560 px, default), `lg` (800 px).

### `FormField`

Children render-prop pattern: `children({ id, 'aria-describedby', 'aria-invalid', 'aria-required' })`. Combines `errors[]` (client-side) and `fieldErrors[]` (server 400 field errors) into a single `<ul role="alert" aria-live="polite">`.

### `Chip`

Three kinds: `priority`, `state`, `risk`. Each value maps to `{ label, icon, color, textColor, borderColor }` via lookup table. Icon + text label renders for greyscale/BR-34 support. `role="status"`, `aria-label="{kind}: {label}"`. Unknown value falls back to neutral with console warning.

**Priority values:** `critical`, `high`, `medium`, `low`
**State values:** `new`, `assigned`, `en_route`, `in_progress`, `on_hold`, `completed`, `closed`, `cancelled`
**Risk values:** `high`, `medium`, `low`

### `ScorePresentation`

Monochrome numeral (font-weight 700, no semantic color — BR-33). 4 px neutral overall progress bar. Per-factor micro-bars using `var(--token-neutral-500)`. No traffic-light or contextual color anywhere.

### `ToastProvider` / `useToast()`

`useToast()` returns `{ toast(message, variant) }`. Variants: `info`, `success`, `warning`, `danger`. Info/success/warning toasts render in `role="status" aria-live="polite"` region; danger toasts render in `role="alert" aria-live="assertive"`. Throttle: at most 1 non-danger toast visible simultaneously. Danger toasts have no auto-dismiss timeout.

---

## Named State Components

All five re-exported from `StateSurface`:

| Export | Variant | Notes |
|---|---|---|
| `EmptyState` | `empty` | No live region. |
| `LoadingState` | `loading` | Skeleton (first load) or inline spinner (`isRefetching`). `role="status" aria-live="polite"`. |
| `DegradedState` | `degraded` | Shows stale-data warning + optional retry button. `aria-live="polite"`. |
| `PermissionDeniedState` | `permission-denied` | Access denied message. No retry. |
| `ErrorState` | `error` | Error message + retry button. `aria-live="assertive"`. |

---

## Mock Transport

`createMockTransport({ latencyMs?, errorCode?, stale? })` returns `{ fetch(endpoint) }` backed by committed JSON fixtures:

| Endpoint | Fixture |
|---|---|
| `dispatcher/workorders` | `src/mocks/fixtures/dispatcher-workorders.json` |
| `technician/jobs` | `src/mocks/fixtures/technician-jobs.json` |
| `manager/kpis` | `src/mocks/fixtures/manager-kpis.json` |
| `customer/requests` | `src/mocks/fixtures/customer-requests.json` |

---

## Component Catalogue

The catalogue route (`src/catalogue/CatalogueRoute.jsx`) renders every primitive and named state variant driven by fixture data. It is buildable in CI without a backend:

```sh
npm run build:catalogue
# outputs to dist-catalogue/
```

Set `VITE_CATALOGUE=true` to serve the catalogue from the main entry point.
