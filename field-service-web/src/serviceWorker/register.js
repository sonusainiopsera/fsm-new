/**
 * @fileoverview Service worker registration — technician PWA (field surface).
 *
 * - Scoped to /technician so other surfaces are unaffected.
 * - Exposes onUpdateReady callback so TechnicianShell can show the skip-waiting
 *   prompt (AC-9: never force-reload mid-shift).
 * - purgeCaches() clears runtime caches on logout (Edge case 5: shared device).
 * - Skip registration in dev to avoid stale-cache confusion with hot reload.
 *
 * SECURITY: Access tokens are never cached; the SW only caches GET responses
 * for the day-list endpoint (see fieldServiceWorker.js).
 */

const SW_PATH = '/field-service-worker.js'

/**
 * @typedef {{
 *   onUpdateReady?: (waitingWorker: ServiceWorker) => void
 * }} RegisterOptions
 */

/**
 * Registers the field service worker. Idempotent — safe to call multiple times.
 *
 * @param {RegisterOptions} [options]
 * @returns {Promise<ServiceWorkerRegistration | null>}
 */
export async function registerFieldServiceWorker(options = {}) {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return null
  }

  if (import.meta.env.DEV) {
    return null
  }

  try {
    const registration = await navigator.serviceWorker.register(SW_PATH, {
      scope: '/technician/',
    })

    // Immediately check if an update is already waiting (page refresh scenario)
    if (registration.waiting) {
      options.onUpdateReady?.(registration.waiting)
    }

    registration.addEventListener('updatefound', () => {
      const installing = registration.installing
      if (!installing) return

      installing.addEventListener('statechange', () => {
        if (installing.state === 'installed' && navigator.serviceWorker.controller) {
          // New SW installed but waiting — prompt user instead of force-reloading
          options.onUpdateReady?.(installing)
        }
      })
    })

    // Reload the page once the new SW takes control (after skip-waiting)
    let refreshing = false
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!refreshing) {
        refreshing = true
        window.location.reload()
      }
    })

    return registration
  } catch (err) {
    if (typeof console !== 'undefined') {
      console.warn('[SW] Registration failed:', { error: String(err) })
    }
    return null
  }
}

/**
 * Posts PURGE_CACHES to the active service worker.
 * Call on logout to clear any cached job data from a shared device.
 */
export async function purgeCaches() {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
  const registration = await navigator.serviceWorker.getRegistration('/technician/')
  if (registration?.active) {
    registration.active.postMessage({ type: 'PURGE_CACHES' })
  }
}

/**
 * Returns true if the response came from the SW cache (stale indicator).
 *
 * @param {Response} response
 * @returns {boolean}
 */
export function isStaleServiceWorkerResponse(response) {
  return response.headers.get('x-sw-stale') === 'true'
}

/**
 * Returns the age in milliseconds of a stale SW-served response.
 *
 * @param {Response} response
 * @returns {number | null}
 */
export function getStaleAgeMs(response) {
  const raw = response.headers.get('x-sw-age-ms')
  return raw !== null ? parseInt(raw, 10) : null
}
