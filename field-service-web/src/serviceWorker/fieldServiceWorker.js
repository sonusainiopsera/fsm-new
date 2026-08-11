/**
 * Field Surface Service Worker
 *
 * Cache-first strategy for the assigned-jobs GET endpoint only.
 * Scope is intentionally narrow:
 *   - Caches: GET /api/v1/work-orders (assigned-jobs list, short max-age)
 *   - Never caches: tokens, authenticated mutation responses, any non-GET request
 *
 * Write queueing is explicitly out of scope. Mutation attempts while offline
 * short-circuit to a NetworkOfflineError surfaced by useNetworkStatus.
 *
 * Security:
 *   - Authorization header values are never stored in the cache (requests with
 *     auth headers pass through the cache key check but responses are stored
 *     without credential context — read-only public cache policy).
 *   - Token refresh requests are excluded by the exact-path cache scope.
 *
 * Cache key: the path and query string of the request URL (no auth header in key).
 * Max-age: 5 minutes (300 seconds). Entries older than max-age are re-fetched
 * on the next connection and the degraded indicator clears automatically.
 */

const CACHE_NAME = 'fsvc-field-v1';
const ASSIGNED_JOBS_PATTERN = /^\/api\/v1\/work-orders(\?.*)?$/;
const MAX_AGE_MS = 5 * 60 * 1000; // 5 minutes

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((key) => key !== CACHE_NAME)
          .map((key) => caches.delete(key))
      )
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  if (!shouldCache(request, url)) return;

  event.respondWith(cacheFirstWithExpiry(request));
});

function shouldCache(request, url) {
  return (
    request.method === 'GET' &&
    ASSIGNED_JOBS_PATTERN.test(url.pathname + url.search.slice(0, url.search.indexOf('&') < 0 ? undefined : undefined)) &&
    ASSIGNED_JOBS_PATTERN.test(url.pathname)
  );
}

async function cacheFirstWithExpiry(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);

  if (cached) {
    const dateHeader = cached.headers.get('sw-cached-at');
    const age = dateHeader ? Date.now() - parseInt(dateHeader, 10) : MAX_AGE_MS + 1;

    if (age < MAX_AGE_MS) {
      return addStalenessHeader(cached, false);
    }

    // Stale — attempt network, fall back to stale cache with degraded indicator
    try {
      const fresh = await fetch(request.clone());
      if (fresh.ok) {
        await putWithTimestamp(cache, request, fresh.clone());
        return fresh;
      }
    } catch {
      // Offline — return stale with staleness indicator
    }
    return addStalenessHeader(cached, true);
  }

  // Not cached — fetch and store
  try {
    const response = await fetch(request.clone());
    if (response.ok) {
      await putWithTimestamp(cache, request, response.clone());
    }
    return response;
  } catch (err) {
    // Offline and not cached
    return new Response(
      JSON.stringify({ error: 'Offline — no cached data available' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } }
    );
  }
}

async function putWithTimestamp(cache, request, response) {
  const headers = new Headers(response.headers);
  headers.set('sw-cached-at', String(Date.now()));
  const stamped = new Response(await response.arrayBuffer(), {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
  await cache.put(request, stamped);
}

function addStalenessHeader(response, isStale) {
  const headers = new Headers(response.headers);
  headers.set('x-sw-stale', isStale ? 'true' : 'false');
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
