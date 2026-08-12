/**
 * Technician PWA Service Worker
 *
 * Caching strategy:
 *  - StaleWhileRevalidate for GET /api/v1/technicians/me/work-orders
 *    with a 12-hour (shift-length) max-age. Stale responses are served
 *    immediately while the network refresh runs in the background.
 *  - NetworkOnly for ALL non-GET requests — no write is ever cached or queued.
 *  - Responds to SKIP_WAITING message from the update-prompt banner.
 *  - Responds to PURGE_CACHES message sent on logout to clear runtime caches.
 *
 * Security:
 *  - Authorization header values are never stored. The cache key is the URL only.
 *  - Token refresh paths are excluded by the exact endpoint scope pattern.
 *
 * Install / activate:
 *  - Does NOT call skipWaiting() on install — waits for explicit user confirmation
 *    via the SKIP_WAITING message so mid-shift workers are not force-reloaded.
 *  - Cleans up any previous fsvc-tech-* caches on activate.
 */

const CACHE_VERSION = 'v1';
const DAY_CACHE     = 'fsvc-tech-day-' + CACHE_VERSION;
const SHIFT_MAX_AGE = 12 * 60 * 60 * 1000; // 12 hours

// Only cache the technician day-list endpoint
const DAY_ENDPOINT_RE = /^\/api\/v1\/technicians\/me\/work-orders(\?.*)?$/;

// ── Lifecycle ─────────────────────────────────────────────────────────────────

self.addEventListener('install', (_event) => {
  // Do NOT skip-waiting here — waiting for SKIP_WAITING message from update prompt.
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k.startsWith('fsvc-tech-') && k !== DAY_CACHE)
          .map((k) => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

// ── Message handler ───────────────────────────────────────────────────────────

self.addEventListener('message', (event) => {
  if (!event.data) return;

  if (event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }

  if (event.data.type === 'PURGE_CACHES') {
    event.waitUntil(
      caches.keys().then((keys) =>
        Promise.all(
          keys.filter((k) => k.startsWith('fsvc-tech-')).map((k) => caches.delete(k))
        )
      )
    );
  }
});

// ── Fetch handler ─────────────────────────────────────────────────────────────

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // NetworkOnly for all mutations
  if (request.method !== 'GET') return;

  // Only handle the technician day-list endpoint
  if (!DAY_ENDPOINT_RE.test(url.pathname)) return;

  event.respondWith(staleWhileRevalidate(request));
});

// ── Caching strategy ──────────────────────────────────────────────────────────

async function staleWhileRevalidate(request) {
  const cache  = await caches.open(DAY_CACHE);
  const cached = await cache.match(request);

  // Fire background revalidation regardless of cache state
  const networkPromise = fetchAndCache(request, cache);

  if (cached) {
    const cachedAtMs = parseInt(cached.headers.get('sw-cached-at') || '0', 10);
    const ageMs      = Date.now() - cachedAtMs;

    if (ageMs <= SHIFT_MAX_AGE) {
      // Fresh enough — return cached immediately; revalidation runs in background
      networkPromise.catch(() => {/* offline — swallow */});
      return withStalenessHeader(cached, false);
    }

    // Beyond shift TTL — must wait for network; fall back to stale on failure
    try {
      return await networkPromise;
    } catch {
      return withStalenessHeader(cached, true);
    }
  }

  // Nothing cached — must wait for network
  try {
    return await networkPromise;
  } catch {
    return new Response(
      JSON.stringify({ error: 'Offline — no cached data available' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } }
    );
  }
}

async function fetchAndCache(request, cache) {
  const response = await fetch(request.clone());
  if (response.ok) {
    const headers = new Headers(response.headers);
    headers.set('sw-cached-at', String(Date.now()));
    const stamped = new Response(await response.arrayBuffer(), {
      status:     response.status,
      statusText: response.statusText,
      headers,
    });
    await cache.put(request, stamped);
  }
  return response;
}

function withStalenessHeader(response, isStale) {
  const headers = new Headers(response.headers);
  headers.set('x-sw-stale', isStale ? 'true' : 'false');
  return new Response(response.body, {
    status:     response.status,
    statusText: response.statusText,
    headers,
  });
}
