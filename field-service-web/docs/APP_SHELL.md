# Application Shell

## Architecture overview

One shell frames all four persona surfaces from a single React codebase.

```
main.jsx
└── AppProviders (composition root)
    ├── QueryClientProvider     (TanStack Query)
    ├── AppearanceProvider      (light/dark/system — see APPEARANCE.md)
    ├── DensityProvider         (comfortable/compact)
    ├── ToastProvider           (notification region)
    ├── AuthProvider            (in-memory access token)
    └── RouterProvider          (React Router 6 data router)
        ├── /dispatch/*  → LazyDispatch   (chunk-dispatch)
        ├── /field/*     → LazyField      (chunk-field)
        ├── /operations/* → LazyOperations (chunk-operations)
        ├── /portal/*    → LazyPortal     (chunk-portal)
        └── /sign-in     → LazySignIn     (chunk-auth)
```

## Authority model — CRITICAL

**Role-derived navigation is a USABILITY affordance only.**

The sidebar filters navigation items by the roles claim from the decoded access
token so each persona sees only the surfaces relevant to their work. This is
a cognitive-clarity improvement — it is **NOT a security control**.

Why this matters (A01, BR-19):
- Hiding a link from the sidebar does not prevent a user from navigating to
  its URL directly.
- Every API endpoint is protected by server-side role and row-scope checks
  independent of the client.
- A hand-crafted URL to an unauthorised route returns a server 403. The
  WO-090 query error mapper surfaces this as the `PermissionDeniedState`
  primitive with no disclosure of whether the resource exists.
- The client never grants or denies access based on token contents alone.

**Never add client-side route guards that return data or block rendering based
solely on the roles claim.** The `filterNavItems()` function in `navigation.js`
is the only place roles affect the UI, and its output is always advisory.

## Sidebar

| State | Width | Trigger |
|---|---|---|
| Expanded | 240px | Default on wide viewports |
| Collapsed (icon rail) | 64px | Click collapse button |
| Drawer (off-canvas) | 240px panel | Viewport < 768px |

Collapsed state is persisted in `localStorage` key `fsvc_sidebar_collapsed`.
Below 768px the drawer mode wins regardless of the stored preference; the
stored preference is preserved for wider viewports.

Icon-only items in the collapsed rail retain their accessible name via
`aria-label` on the `<a>` element. Tooltip disclosure is handled natively by
the `title` attribute.

## Code splitting

Each surface is a separate Rollup chunk:

| Surface | Chunk | Entry roles |
|---|---|---|
| Dispatch Board | `chunk-dispatch` | DISPATCHER, ADMIN, MANAGER |
| Field (My Jobs) | `chunk-field` | TECHNICIAN |
| Operations Dashboard | `chunk-operations` | MANAGER, ADMIN |
| Customer Portal | `chunk-portal` | CUSTOMER |
| Sign In | `chunk-auth` | (unauthenticated) |

Charting libraries (Recharts, D3) are bundled into `chunk-operations` only.
A technician navigating to `/field` never downloads the charting bundle.

**CI size budget:** the `chunk-field` entry payload must remain ≤ 100 kB
gzipped. This is asserted in `scripts/check-bundle-size.mjs`.

## Service worker (field surface only)

The field surface registers `fieldServiceWorker.js` on mount. The worker:

- Caches `GET /api/v1/work-orders` responses (assigned-jobs list) for 5 minutes.
- Never caches tokens, mutation responses, or any non-GET request.
- Returns a stale cache hit with `x-sw-stale: true` when offline and the
  cache is older than 5 minutes — the caller surfaces a `DegradedState` indicator.
- Returns 503 JSON when offline with no cached data.

Write queueing is **explicitly out of scope**. When offline, mutation attempts
throw `NetworkOfflineError` (from `useNetworkStatus`) which the mutation handler
maps to the not-connected retry affordance. No write is queued or silently
retried.

## Provider mount order

Providers are mounted in `AppProviders.jsx` in a documented order. **Do not
add providers outside this file.** Later epics hang their providers here so
every surface inherits consistent context.

```
QueryClientProvider  — outermost so providers can issue queries during init
AppearanceProvider   — wraps router so appearance switch works on every surface
DensityProvider      — persona density default (WO-091 defines variants)
ToastProvider        — above router so toast region survives route transitions
AuthProvider         — holds decoded in-memory access token
RouterProvider       — innermost; consumes all contexts above
```

## Error boundary

`ErrorBoundary` (class component) wraps the route `<Outlet>` in `AppShell`.
It is keyed by `location.pathname` so navigating away from an errored route
resets the boundary automatically.

On error:
- Renders `ErrorState` with the `traceId` from the structured API error
  envelope when present.
- Never renders a stack trace or internal detail (A10).
- Provides a "Try again" retry button that calls `onReset` and resets state.

## Landmarks and keyboard navigation

| Landmark | Role | Element |
|---|---|---|
| Banner | `banner` | `<header>` in TopBar |
| Navigation | `navigation` | `<nav aria-label="Primary navigation">` in Sidebar |
| Main content | `main` | `<main id="main-content">` in AppShell |

Tab order: skip-to-content link → sidebar → top bar → main content.

The skip-to-content link is visually hidden until focused. It targets
`#main-content`.

## Files

| File | Purpose |
|---|---|
| `src/app/AppProviders.jsx` | Composition root — all providers in one place |
| `src/app/router.jsx` | `createBrowserRouter` with lazy surface chunks |
| `src/app/AppShell.jsx` | Shell layout (CSS Grid, landmarks, Outlet) |
| `src/app/Sidebar/Sidebar.jsx` | Role-derived navigation sidebar |
| `src/app/Sidebar/useSidebarCollapse.js` | Collapse state with localStorage persistence |
| `src/app/TopBar/TopBar.jsx` | Banner: appearance switch, network indicator, account menu |
| `src/app/ErrorBoundary.jsx` | Route-level error boundary — ErrorState with traceId |
| `src/app/navigation.js` | Declarative nav manifest + `filterNavItems()` |
| `src/app/AuthContext.js` | In-memory access token context (no localStorage) |
| `src/app/useNetworkStatus.js` | `isOnline`/`assertOnline()` — offline write guard |
| `src/serviceWorker/fieldServiceWorker.js` | Assigned-jobs cache-first SW |
| `src/serviceWorker/registerServiceWorker.js` | SW registration (field surface only) |
