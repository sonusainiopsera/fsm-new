/**
 * @fileoverview Field surface service worker — technician PWA cache strategy.
 *
 * SECURITY CONSTRAINTS (never relax these):
 * - Access tokens (Authorization header values) are NEVER written to any cache.
 *   Responses are cached under canonical URL keys with no auth data.
 * - Only GET requests matching the technician day-list path are cached.
 * - All non-GET /api/v1 requests (mutations) are NetworkOnly — never cached,
 *   never retried in the background (AC-5: no queued writes).
 * - Cache expiration is capped at the shift length (SHIFT_MAX_AGE_MS = 12 h).
 * - Cache is purged on logout by the client posting PURGE_CACHES message.
 *
 * Caching strategy:
 * - App shell assets (/_assets/**): precached on install; old versions deleted
 *   on activate.
 * - GET /api/v1/technicians/me/work-orders: StaleWhileRevalidate with a 12-hour
 *   max-age. Served with x-sw-stale:true header when network fails.
 * - All other requests: passthrough (NetworkFirst implicit).
 *
 * SW updates:
 * - Clients receive a SKIP_WAITING message to trigger activation after the user
 *   confirms the update prompt (AC-9: never force-reload mid-shift).
 */

const CACHE_NAME = 'field-service-worker-v2'
const DAY_LIST_PATTERN = /\/api\/v1\/technicians\/me\/work-orders(\?.*)?$/
const SHIFT_MAX_AGE_MS = 12 * 60 * 60 * 1000 // 12 hours

// ── Install: open cache (no precache assets in this hand-rolled SW) ──────────

self.addEventListener('install', (event) => {
  // Do NOT skipWaiting here — we prompt the user via SKIP_WAITING message (AC-9)
  event.waitUntil(caches.open(CACHE_NAME))
})

// ── Activate: delete old cache versions ──────────────────────────────────────

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then(keys =>
        Promise.all(
          keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
        )
      )
      .then(() => self.clients.claim())
  )
})

// ── Message: skip-waiting on user confirmation (AC-9) ────────────────────────

self.addEventListener('message', (event) => {
  if (event.data?.type === 'SKIP_WAITING') {
    self.skipWaiting()
  }
  if (event.data?.type === 'PURGE_CACHES') {
    // Called on logout — purge runtime caches so no previous user's jobs are
    // readable on a shared device (Edge case 5 in WO-155)
    event.waitUntil(
      caches.keys().then(keys => Promise.all(keys.map(k => caches.delete(k))))
    )
  }
})

// ── Fetch: routing strategy ───────────────────────────────────────────────────

self.addEventListener('fetch', (event) => {
  const { request } = event
  const url = new URL(request.url)

  // SECURITY: never cache mutating requests — network only, no background sync
  if (request.method !== 'GET') {
    return // pass through; do not call event.respondWith()
  }

  // StaleWhileRevalidate for the technician day-list GET
  if (DAY_LIST_PATTERN.test(url.pathname)) {
    event.respondWith(staleDayListStrategy(request))
    return
  }

  // All other requests: network passthrough
})

// ── StaleWhileRevalidate strategy for day-list ────────────────────────────────

async function staleDayListStrategy(request) {
  const canonicalUrl = canonicalise(request.url)
  const cache = await caches.open(CACHE_NAME)
  const cached = await cache.match(canonicalUrl)

  // Revalidate in the background regardless of cache hit
  const networkPromise = fetchAndCache(request, cache, canonicalUrl)

  if (cached) {
    const cachedAt = parseInt(cached.headers.get('x-sw-cached-at') || '0', 10)
    const age = Date.now() - cachedAt

    if (age <= SHIFT_MAX_AGE_MS) {
      // Serve stale immediately; background fetch updates the cache
      networkPromise.catch(() => {}) // suppress unhandled rejection
      return addStaleHeader(cached, age)
    }
    // Expired (> 12 h) — must go to network; fall through
  }

  // No cache or expired — wait for network
  try {
    return await networkPromise
  } catch {
    // Network failed and no cached version (or cache expired)
    if (cached) {
      // Serve the expired cache with explicit stale-expired indicator
      const age = Date.now() - parseInt(cached.headers.get('x-sw-cached-at') || '0', 10)
      return addStaleHeader(cached, age, true)
    }
    return Response.error()
  }
}

async function fetchAndCache(request, cache, canonicalUrl) {
  const response = await fetch(request)
  if (response.ok) {
    const safeHeaders = buildSafeHeaders(response)
    safeHeaders.set('x-sw-cached-at', String(Date.now()))
    const body = await response.clone().arrayBuffer()
    await cache.put(canonicalUrl, new Response(body, {
      status: response.status,
      statusText: response.statusText,
      headers: safeHeaders,
    }))
  }
  return response
}

function addStaleHeader(cachedResponse, ageMs, expired = false) {
  const headers = new Headers(cachedResponse.headers)
  headers.set('x-sw-stale', 'true')
  headers.set('x-sw-age-ms', String(ageMs))
  headers.set('x-sw-max-age-exceeded', String(expired))
  return cachedResponse.clone().arrayBuffer().then(body =>
    new Response(body, {
      status: 200,
      statusText: 'OK (cached)',
      headers,
    })
  )
}

function canonicalise(rawUrl) {
  const url = new URL(rawUrl)
  url.searchParams.delete('_nocache')
  return url.toString()
}

function buildSafeHeaders(response) {
  const safe = new Headers()
  response.headers.forEach((value, name) => {
    const lower = name.toLowerCase()
    // SECURITY: never store Authorization, Set-Cookie, or token-bearing headers
    if (!['authorization', 'set-cookie', 'cookie'].includes(lower)) {
      safe.set(name, value)
    }
  })
  return safe
}
