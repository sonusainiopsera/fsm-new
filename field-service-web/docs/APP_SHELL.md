# Application Shell

One composition root, four persona surfaces, role-derived navigation for usability, server-enforced authorisation for security.

## Provider Order

```
AppProviders (src/app/AppProviders.jsx)
├── QueryClientProvider    — TanStack Query (staleTime: 30s, retry: 1)
├── AuthContext.Provider   — in-memory access token + roles + userId
├── AppearanceProvider     — data-appearance on <html>, localStorage mirror
├── DensityProvider        — comfortable / compact density toggle
├── ToastProvider          — polite + assertive toast live regions
└── AppRouter              — React Router 6 → AppShell → Outlet
```

**Rule:** Every later epic mounts inside the router's `<Outlet>`. Never re-mount any of these providers inside a surface route — doing so tears down the QueryClient cache, resets toast state, and breaks Appearance synchronisation.

## Security Posture (A01, BR-19)

**Role-derived navigation is a USABILITY control only. It hides navigation items that a user cannot exercise. It is NOT a security boundary.**

Security is enforced server-side:
1. Every API request carries a JWT Bearer token validated by the Spring OAuth2 Resource Server.
2. A server 403 response is mapped by `RouteErrorBoundary` to `PermissionDeniedState`.
3. The client never grants access on the basis of the roles claim alone.
4. No router guard returns data — protected routes render their loader result.

## Route Structure

```
/ (AppShell)
├── index             → redirect /dispatch
├── dispatch/*        → DispatchSurface   (lazy)
├── field/*           → FieldSurface      (lazy, SW registered)
├── operations/*      → OperationsSurface (lazy, charting chunk)
├── portal/*          → PortalSurface     (lazy)
└── settings          → placeholder
/sign-in              → SignIn            (lazy)
*                     → NotFound
```

## Navigation Manifest

Defined in `src/app/navigation.js`. Each entry has:

| Field | Description |
|---|---|
| `key` | Unique identifier (used in tests for fixture assertions) |
| `path` | React Router path |
| `label` | Visible text label |
| `icon` | Icon key (resolved to text glyph; SVG sprite added in a future WO) |
| `allowedRoles` | Roles that see this item — **usability filter only** |
| `surface` | Which surface group this belongs to |

`filterNavForRoles(roles)` returns the filtered list. Unrecognised roles log a structured warning and produce no items rather than crashing the shell.

## Bundle Splitting

`vite.config.js` `manualChunks` produces:

| Chunk | Contents |
|---|---|
| `vendor` | react, react-dom, and other shared dependencies |
| `vendor-router` | react-router-dom, @remix-run/* |
| `vendor-query` | @tanstack/react-query |
| `surface-dispatch` | Dispatch console routes |
| `surface-operations` | Operations dashboard + charting bundle |
| `surface-field` | Technician PWA routes |
| `surface-portal` | Customer portal routes |
| `charting` | recharts / d3 / any charting library |

The technician (`surface-field`) entry never includes `charting` or `surface-dispatch` code.

## Service Worker (Field Surface)

Registered exclusively for the technician surface by `src/serviceWorker/register.js`.

- **Scope**: `/field/`
- **Cached**: `GET /api/v1/technician/jobs` and `/api/v1/technician/jobs/assigned` responses only
- **Max-age**: 5 minutes
- **Never cached**: Authorization header values, mutation responses (POST/PUT/PATCH/DELETE)
- **Offline serving**: Cached response returned with `x-sw-stale: true` header; client surface renders `DegradedState`
- **Chunk-load failure**: A deploy that replaces hashed assets causes `ChunkLoadError`; the error boundary detects this and prompts a reload

The SW source is at `src/serviceWorker/fieldServiceWorker.js`. For production, this file must be served at `/field-service-worker.js` — place it in `public/field-service-worker.js` or add a Vite copy plugin.

## Offline Mutation Guard

`useNetworkStatus()` exposes `guardMutation()`. Any mutation path must call it before submitting:

```js
const { guardMutation } = useNetworkStatus()

function handleSubmit() {
  if (!guardMutation()) {
    // Show not-connected retry affordance — NEVER queue or optimistically confirm
    return
  }
  submitMutation()
}
```

**No write queueing, no silent retry** — the shell surfaces a clear not-connected state and the user retries manually (AC-8).

## Error Boundary

`ErrorBoundary` (class component) wraps the route outlet and catches:
- Component-level errors via `getDerivedStateFromError`
- Route-level errors via the `error` prop (from `useRouteError()` in `RouteErrorPage`)

A10 compliance: stack traces and internal details are never rendered. Only the `traceId` from the structured error envelope is surfaced for support correlation.

## CSS Layout

`AppShell.module.css` uses CSS Grid:

```
┌─────────────────────────────────────────────┐
│ sidebar  │  topbar (banner, sticky 56px)     │
│  nav     ├─────────────────────────────────── │
│ 240/64px │  main (route outlet)              │
│          │  max-width 1440px                 │
└─────────────────────────────────────────────┘
```

Below 768px: sidebar moves to off-canvas drawer, grid collapses to single column.

## Appearance Switch

The `TopBar` appearance toggle is reachable from every surface (AC-6). It calls `setPreference()` from `useAppearance()`, which:
1. Updates React state
2. Writes to localStorage (`fs-appearance`)
3. Mutates `data-appearance` on `<html>` (single DOM write, <100ms)
4. Fires `onPreferenceChange` callback for the preferences API PUT

## Keyboard Traversal Order

1. Skip-to-content link (visible on `:focus`)
2. TopBar controls (appearance switch, account menu)
3. Sidebar navigation items (Tab through links)
4. Main content (`id="main-content"`, `tabIndex={-1}` for programmatic focus)

Landmarks: `<header role="banner">`, `<nav aria-label="Primary navigation">`, `<main>`.
