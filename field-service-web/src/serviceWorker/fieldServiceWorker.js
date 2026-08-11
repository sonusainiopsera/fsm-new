/**
 * @fileoverview Field surface service worker — read-only assigned-jobs cache.
 *
 * SECURITY CONSTRAINTS:
 * - Access tokens (Authorization header values) are NEVER written to the cache.
 *   Responses are cached under a canonical URL key (no auth header in key).
 * - Only GET requests to the assigned-jobs path are intercepted.
 * - Mutation responses (POST/PUT/PATCH/DELETE) are never cached.
 * - The cache is scoped to a short max-age so stale data is always detectable.
 *
 * When offline, the cached assigned-jobs list is served with an x-sw-stale
 * header so the client can surface the DegradedState indicator (AC-7).
 */

const CACHE_NAME = 'field-assigned-jobs-v1'
const ASSIGNED_JOBS_PATTERN = /\/api\/v1\/technician\/jobs(\/assigned)?(\?.*)?$/
const MAX_AGE_MS = 5 * 60 * 1000 // 5 minutes

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  // Remove any caches from previous SW versions
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  const { request } = event

  // Only handle GET requests matching the assigned-jobs endpoint
  if (request.method !== 'GET' || !ASSIGNED_JOBS_PATTERN.test(new URL(request.url).pathname)) {
    return
  }

  // Canonical cache key — exclude Authorization header so no token is cached
  const canonicalUrl = new URL(request.url)
  canonicalUrl.searchParams.delete('_nocache')
  const cacheKey = canonicalUrl.toString()

  event.respondWith(
    fetch(request).then(async (networkResponse) => {
      if (networkResponse.ok) {
        const cache = await caches.open(CACHE_NAME)
        // Strip Authorization and other sensitive headers before caching
        const safeHeaders = new Headers()
        networkResponse.headers.forEach((value, name) => {
          const lower = name.toLowerCase()
          if (lower !== 'authorization' && lower !== 'set-cookie') {
            safeHeaders.set(name, value)
          }
        })
        safeHeaders.set('x-sw-cached-at', String(Date.now()))
        safeHeaders.set('content-type', networkResponse.headers.get('content-type') || 'application/json')

        const body = await networkResponse.clone().arrayBuffer()
        const cachedResponse = new Response(body, {
          status: networkResponse.status,
          statusText: networkResponse.statusText,
          headers: safeHeaders,
        })
        cache.put(cacheKey, cachedResponse)
      }
      return networkResponse
    }).catch(async () => {
      // Network failed — serve stale cache with degraded indicator header
      const cache = await caches.open(CACHE_NAME)
      const cached = await cache.match(cacheKey)
      if (cached) {
        const cachedAt = parseInt(cached.headers.get('x-sw-cached-at') || '0', 10)
        const age = Date.now() - cachedAt
        const staleHeaders = new Headers(cached.headers)
        staleHeaders.set('x-sw-stale', 'true')
        staleHeaders.set('x-sw-age-ms', String(age))
        staleHeaders.set('x-sw-max-age-exceeded', String(age > MAX_AGE_MS))

        const body = await cached.arrayBuffer()
        return new Response(body, {
          status: 200,
          statusText: 'OK (cached)',
          headers: staleHeaders,
        })
      }
      // No cache — let the request fail naturally
      return Response.error()
    })
  )
})
