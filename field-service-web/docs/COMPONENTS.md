# Component Primitive Library

> **Phase 2 Inventory Freeze** — This inventory is frozen at Phase 2 exit (BR-31).
> No new primitives may be added in later epics without a formal change request.
> Per-screen bespoke styling is prohibited; all UI must compose from these primitives.

## Exported Inventory

| Component | Location | Description |
|---|---|---|
| `Button` | `Button/Button.jsx` | Primary, secondary, tertiary, ghost, destructive variants; touch target variant |
| `PageHeader` | `PageHeader/PageHeader.jsx` | Title + breadcrumb + exactly one primary action |
| `KpiCard` | `KpiCard/KpiCard.jsx` | Label, 30px tabular value, delta chip, target track, sparkline |
| `DataTable` | `DataTable/DataTable.jsx` | Headless column definitions, density, sticky header, responsive |
| `DetailDrawer` | `DetailDrawer/DetailDrawer.jsx` | Side drawer, elevation-2, focus trap |
| `Modal` | `Modal/Modal.jsx` | Centre overlay, elevation-2, focus trap |
| `FormField` | `FormField/FormField.jsx` | Label, help, required indicator, inline errors with aria wiring |
| `Chip` | `Chip/Chip.jsx` | Priority, state, risk variants (colour + text + icon) |
| `ScorePresentation` | `ScorePresentation/ScorePresentation.jsx` | Monochrome numeral, 4px neutral track, per-factor micro-bars |
| `ToastProvider` + `useToast` | `Toast/ToastProvider.jsx` | Queued notifications, aria-live, danger never auto-dismisses |
| `StateSurface` | `StateSurface/StateSurface.jsx` | Five named states (see below) |
| `EmptyState` | `StateSurface/StateSurface.jsx` | Empty-state alias |
| `LoadingState` | `StateSurface/StateSurface.jsx` | Loading alias (skeleton / inline) |
| `DegradedState` | `StateSurface/StateSurface.jsx` | Stale-data alias |
| `PermissionDeniedState` | `StateSurface/StateSurface.jsx` | 403 alias |
| `ErrorState` | `StateSurface/StateSurface.jsx` | Error alias with optional retry |

All imports must come from the single entry point:

```js
import { Button, Chip, DataTable } from '../components';
```

---

## Per-Component Binding Rules

### Button
- Exactly five variants: `primary | secondary | tertiary | ghost | destructive`.
- Unknown variant → `console.warn` + neutral rendering (no crash).
- `touch` prop enlarges min-height to 44 px for the technician surface.
- Focus ring: 2 px `--color-accent-base` with 2 px offset.

### PageHeader
- **One primary action only.** Passing an array of two or more elements throws in development.
- Additional actions must be in `secondaryActions` and will render as ghost/tertiary.
- Breadcrumb last item receives `aria-current="page"`.

### KpiCard
- Label: `--fs-xs` (12–13 px) muted.
- Value: 30 px tabular-figure numeral.
- Delta chip: omitted entirely when `delta` is `null` or `undefined` — never render zero as a dash.
- Hover: border token changes only. No shadow change.
- Cards use `--radius-card` (10 px), `--space-4` padding (16 px), hairline `--color-border`, no `box-shadow` at rest.

### DataTable
- Comfortable rows: 40 px (`2.5rem`). Compact rows: 32 px (`2rem`).
- Sticky header, hairline row separators (`--color-border`).
- **No zebra striping.**
- Numeric columns right-aligned with `font-variant-numeric: tabular-nums`.
- Sortable columns always show affordance (`↕`) even when not the active sort.
- Active sort column uses `↑` or `↓`.
- Selected row: 2 px `--color-accent-base` inset on leading edge (left border).
- Zero-row table renders `EmptyState` inside the table frame — the header and density toggle remain visible.
- Long text: `text-overflow: ellipsis; overflow: hidden; white-space: nowrap` with `title` attribute on cell.
- Responsive: collapses to stacked cards below 768 px via `ResizeObserver` (container width, not viewport).
- Card mode preserves declared column order and all `rowActions`.

### DetailDrawer / Modal
- Both use elevation level 2 (`--elevation-2`) — two-elevation ceiling.
- Both use `--scrim` for the backdrop.
- Both use `--radius-overlay` (14 px) for the panel.
- Focus trap: Tab and Shift+Tab cycle within the panel.
- Escape closes immediately.
- Focus is restored to the element that opened the overlay on close.
- Overlay is labelled by its heading via `aria-labelledby`.
- Scrim click closes.
- `prefers-reduced-motion`: animation durations zeroed by `base.css` — no information loss.

### FormField
- `aria-invalid="true"` set on the child control when `errors` is non-empty.
- `aria-describedby` chains helpId + all errorIds.
- Multiple errors for the same field are all rendered — no deduplication.
- Required indicator uses `aria-hidden="true"` on the `*` glyph; `aria-required` is set on the control.

### Chip
- Every value conveys **colour + text label + distinct icon/shape** (BR-34).
- Unknown enum value → neutral fallback + `console.warn`. Never crash.
- Priority values: `urgent | high | normal | low`
- State values: `new | assigned | en_route | in_progress | on_hold | completed | closed | cancelled`
- Risk values: `high | medium | low`

### ScorePresentation
- **Monochrome only.** Track and fill use `--color-neutral-*` tokens.
- No semantic colour (`danger`, `success`, `warning`) on score fills.
- No medal, rosette, star, trophy or celebratory CSS class anywhere.
- Per-factor bars have visible text labels (not aria-only).
- `role="region"` with `aria-label` for the score container.

### StateSurface
- **All five states must use this primitive.** No bespoke empty/error markup.
- `loading` on first load: skeleton lines.
- `loading` with `isRefetch=true`: quiet inline dot indicator (no skeleton).
- `permission-denied`: never renders a retry button even if `onRetry` supplied.
- `error` / `degraded`: optional retry button via `onRetry` prop.
- `error` / `permission-denied` use `aria-live="assertive"`.
- All other states use `aria-live="polite"`.

### ToastProvider / useToast
- At most one visible informational toast at a time (throttled by variant).
- Danger toasts **never** auto-dismiss.
- Non-danger toasts auto-dismiss after 5 seconds.
- Danger toast: `role="alert"`, `aria-live="assertive"`.
- Other toasts: `role="status"`, `aria-live="polite"`.

---

## Density Context

```js
import { DensityProvider, useDensity } from '../density/DensityContext';
```

- Defaults to `comfortable`.
- `compact` mode reduces DataTable row height to 32 px, Chip height, and FormField label size.
- WO-091 layers persona defaults on top via the `density` prop on `DensityProvider`.

---

## Out of Scope

The following are explicitly excluded from this inventory:

- Decorative illustrations
- Per-tenant theming or user-authored themes
- Charts and graphs (belong to the Operations Dashboard epic)
- Navigation shell (AppShell, Sidebar, TopBar — belong to WO-091)

---

## Operator Runbook — Catalogue Smoke Check

The catalogue route (`src/catalogue/CatalogueRoute.jsx`) renders every primitive from committed fixtures. To run it locally:

```sh
cd field-service-web
npm install
npm run dev
# navigate to http://localhost:5173
```

The `build:node` CI step includes `vite build`, which compiles the catalogue. A build failure means a component broke.
