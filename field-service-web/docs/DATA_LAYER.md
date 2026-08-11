# Data Layer — Transport Contract

> WO-186 · Q6 compensating control · See also: ADR-0008 (parts consumption status code)

## Overview

Every screen consumes one shared data layer (`src/api/`) that enforces the platform's transport contract. No HTTP client, retry logic, token refresh, or SSE connection may be re-implemented per screen.

---

## Modules

| Module | Responsibility |
|---|---|
| `tokenStore.js` | In-memory access token; single-flight refresh; subscriber queue |
| `http.js` | Fetch wrapper; Auth header; Idempotency-Key; 401 interceptor |
| `errors.js` | Normalise server envelope → `ClientError`; `retryable` flag |
| `stateMapping.js` | `ClientError` → WO-087 `SurfaceVariant` |
| `pagination.js` | `PageQuery` builder (size ≤ 50); envelope parser |
| `queryClient.js` | Configured TanStack Query 5.x client |
| `useConditionalQuery.js` | ETag/If-None-Match conditional polling |
| `sseClient.js` | Single-use stream ticket; EventSource; backoff |
| `eventKeyMap.js` | SSE event type → TanStack Query key invalidation |

---

## Rule: 4xx responses are never retried

Retrying a 422 guard refusal or a 409 illegal transition is a form of failing open (OWASP A10). The `retryable` flag on `ClientError` is **always `false`** for any 4xx status including 409, 422, and 429. The `retryPredicate` in `queryClient.js` enforces this for every query and mutation.

```
400 VALIDATION_FAILED   → false
401 UNAUTHENTICATED     → false
403 FORBIDDEN           → false
404 NOT_FOUND           → false
409 CONFLICT            → false  ← illegal transition / optimistic lock
422 UNPROCESSABLE       → false  ← business guard refusal (e.g. insufficient stock)
429 RATE_LIMITED        → false  ← honour Retry-After; surface rate-limited state
5xx / network           → true   ← jittered exponential backoff, ≤ 3 attempts
```

---

## Access Token — Memory Only

The access token lives in JavaScript module scope (`tokenStore.js`). It **must never** be written to:
- `localStorage`
- `sessionStorage`
- A cookie writable by JS
- A service-worker cache
- A URL parameter

SSE authentication uses a single-use stream ticket (60 s TTL) instead of the bearer token.

---

## Silent Refresh Protocol

1. A 401 response triggers `refreshToken()` in `tokenStore.js`.
2. `refreshToken()` guards concurrency with a module-scoped `Promise` — exactly one refresh call is in flight at any time.
3. All concurrent 401 callers queue via `queueBehindRefresh()` and receive the new token once the single refresh resolves.
4. On refresh failure: `signOut()` is called, the in-memory token is cleared, the query cache is cleared, and the user is routed to sign-in. No retry loop occurs.

**Test assertion:** ten concurrent 401 responses produce exactly one refresh call.

---

## Conditional Polling (ETag / 304)

`useConditionalQuery.js` implements ETag-conditional polling:

- Sends `If-None-Match: <stored-etag>` on every poll.
- **304 Not Modified**: returns `undefined`. TanStack Query keeps the existing cached data, no re-render occurs, and the staleness clock resets.
- **200 OK**: stores the new `ETag` header value for the query key.

Polling cadences:
- `DASHBOARD_INTERVAL = 30_000` ms — dispatcher / ops dashboard widgets
- `PORTAL_INTERVAL = 60_000` ms — customer portal queries

---

## SSE Client — Stream Ticket Protocol

```
1. GET /api/v1/auth/stream-ticket   (POST, Bearer token, 60 s TTL)
   → { ticket: "<opaque string>" }
2. new EventSource("/api/v1/stream?ticket=<ticket>")
3. Connection drop → backoff (jittered exp, cap 60 s) → new ticket → reconnect
```

**The access token never appears in a URL.**

On event arrival, `eventKeyMap.js` maps the event type to TanStack Query key prefixes. Matching keys are invalidated so affected views refetch within the sub-two-second alert budget.

---

## Status Code → Named State Mapping

| HTTP Status | `SurfaceVariant` | Notes |
|---|---|---|
| 403 | `permission-denied` | Generic message; no existence disclosure |
| 401 | `permission-denied` | Callers should redirect to sign-in |
| 429 | `degraded` | Honour `Retry-After`; suppress further requests |
| 503 | `degraded` | Provider unavailable |
| 409 | `error` | Server message surfaced verbatim |
| 422 | `error` | Server message surfaced verbatim (e.g. INSUFFICIENT_STOCK) |
| 400 | `error` | `fieldErrors` passed directly to `FormField` for inline display |
| 5xx | `error` | Generic message; retry with backoff |
| network | `error` | Connectivity message |

---

## Pagination

All collection queries use `buildPageQuery()` to construct request parameters:

```js
import { buildPageQuery, toQueryString } from '../api/pagination.js'

const query = buildPageQuery({ page: 0, size: 100, sort: 'createdAt' })
// → { page: 0, size: 50, sort: 'createdAt' }  ← clamped to MAX_PAGE_SIZE=50
```

The server-enforced maximum page size is 50. Client requests above 50 are **clamped before dispatch** so the server never receives an out-of-range request.

---

## Q6 Controls

- `tsc --checkJs --strict` (tsconfig.json) provides static type checking across all JS source.
- `scripts/generate-api-client.mjs` generates JSDoc typedefs and endpoint accessors from the OpenAPI schema. Run with `node scripts/generate-api-client.mjs`. The `build:node` CI stage invokes this script and checks for drift.
- Boundary runtime validation in `http.js` rejects responses that do not match the expected envelope shape.
