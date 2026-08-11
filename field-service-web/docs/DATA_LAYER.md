# Data Layer

Single TanStack Query–based data layer that enforces the platform's transport contract. Every screen consumes this layer; no screen re-derives retry logic, token refresh, or SSE authentication.

## Transport contract

| Status | Meaning | Client action |
|--------|---------|---------------|
| 200 | Success | Parse response envelope |
| 204 | No content | Return null |
| 304 | Not Modified | Use cached data — no re-render |
| 400 | Validation failure | Expose fieldErrors inline; do not retry |
| 401 | Unauthenticated | Single silent refresh then replay; sign out on failure |
| 403 | Forbidden | Render PermissionDeniedState; do not disclose existence |
| 404 | Not Found | Render ErrorState |
| 409 | Illegal transition / optimistic-lock conflict | Render ErrorState with server message; do not retry |
| 422 | Business-guard refusal | Render ErrorState with server message; **never retry** |
| 429 | Rate limited | Suppress key until Retry-After elapses; render degraded |
| 503 | Provider degradation | Render DegradedState; retry up to 3 times with backoff |

## No-retry-on-4xx rule (A10)

**4xx responses MUST NEVER be retried.** Retrying a 422 guard refusal or a 409 illegal transition amounts to failing open (OWASP A10 — Security Misconfiguration).

The retry predicate in `src/api/queryClient.js` hard-codes `retryable = false` for all 4xx statuses including 409, 422, and 429. This is also tested against every 4xx code in `__tests__/queryClient.test.js`.

## Modules

### `src/api/tokenStore.js`
Module-scoped access token. No localStorage, no sessionStorage, no URL, no service-worker cache. Single-flight `refresh()` guard prevents duplicate refresh calls. Subscribers are notified on set/clear for request replay.

### `src/api/http.js`
fetch wrapper. Base URL `/api/v1`. Attaches `Authorization: Bearer <token>` from tokenStore. `credentials: include` only for `/auth` paths. `Idempotency-Key` (UUID) on POST/PUT/PATCH/DELETE. On 401: triggers single-flight refresh via tokenStore, then replays the original request. Boundary runtime validation rejects non-object responses.

### `src/api/errors.js`
`ClientError` class: `status`, `code`, `message`, `fieldErrors`, `traceId`, `retryable`, `retryAfterMs`. `normalizeError(response, body)` parses the platform envelope. `networkError(cause)` wraps fetch throws (status 0, retryable: true).

### `src/api/queryClient.js`
Configured `QueryClient` singleton. `staleTime: 30s`, `gcTime: 5m`, `refetchOnWindowFocus: false`. Retry: `shouldRetry(failureCount, error)` — false for 4xx, true for 5xx/network up to 3 attempts. Backoff: `jitteredBackoff(attempt)` — capped exponential with full jitter (delay × (0.5 + rand × 0.5)).

### `src/api/stateMapping.js`
`mapErrorToState(error, meta)` → named state primitive. `getQueryState(queryResult, opts)` for TanStack result objects. Used by screens to render `<StateSurface>` without per-status branching.

### `src/api/pagination.js`
`buildPageParams({ page, pageSize, sort })` — clamps `pageSize` to 50 before dispatch. `parsePaginatedResponse(envelope)` — extracts `data`, `page` metadata, `_links`. `buildKeysetParams` for recommendation/deep-search endpoints.

### `src/api/useConditionalQuery.js`
ETag-conditional polling hook. Stores ETags per query key. On refetch: sends `If-None-Match`. On 304: returns cached data with no re-render. Named presets: `useDashboardQuery` (30s), `usePortalQuery` (60s).

### `src/api/sseClient.js`
`createSseClient(streamPath, { onMessage, onError, onOpen })`. Requests single-use stream ticket via `POST /api/v1/auth/stream-ticket` (bearer-authenticated). Opens `EventSource` with `?ticket=<ticket>`. Re-tickets on every reconnect — consumed tickets are never reused. Reconnect: capped jittered exponential backoff.

### `src/api/eventKeyMap.js`
Maps SSE event types to TanStack Query key arrays. `handleSseEvent(event)` invalidates mapped keys on arrival (synchronous, sub-2s alert budget). Event types: `at-risk`, `breach`, `state-change`, `parts-consumed`, `parts-returned`.

## SSE authentication

The browser `EventSource` API cannot set an `Authorization` header. Authentication uses single-use stream tickets:

1. Client calls `POST /api/v1/auth/stream-ticket` with `Authorization: Bearer <token>`.
2. Server issues a 60-second TTL ticket, IP-bound.
3. Client opens `EventSource` with `?ticket=<ticket>`.
4. Ticket is consumed on first connection — a replay attempt yields 401.
5. On reconnect, client requests a fresh ticket before opening a new connection.

## Staleness degradation

A `staleBeyondMs` meta option on `useConditionalQuery` drives the `DegradedState` indicator when polling has failed for longer than the configured window. Screens are never shown silently stale operational data.

## Idempotency

The http client generates a `crypto.randomUUID()` Idempotency-Key on every mutating request. The key is stable for the lifetime of the request — if the user retries after a network timeout, the same key must be passed (callers can supply an explicit key via the `headers` option).

## Generating the API client

```bash
node scripts/generate-api-client.mjs          # write src/api/generated/
node scripts/generate-api-client.mjs --check  # assert no drift (CI gate)
```

The generator reads `app/src/test/resources/openapi-snapshot.json` and emits:
- `src/api/generated/types.js` — JSDoc typedefs per OpenAPI schema component
- `src/api/generated/endpoints.js` — Typed endpoint accessors with boundary validation

Both files are committed. The `--check` mode is wired into `build:node` so drift is a build failure.
