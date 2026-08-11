/**
 * @fileoverview Service worker registration — field surface only.
 *
 * Registered exclusively for the technician surface to cache the assigned-jobs
 * list for read access when offline. The SW never caches tokens or authenticated
 * mutation responses (WO-185 constraint).
 *
 * The SW file is served at /field-service-worker.js — in Vite production builds,
 * the source at src/serviceWorker/fieldServiceWorker.js must be copied to the
 * public directory or referenced via a Vite plugin.
 */

const SW_PATH = '/field-service-worker.js'

/**
 * Registers the field service worker. Safe to call multiple times (idempotent).
 * Logs a structured warning if registration fails but never throws.
 */
export async function registerFieldServiceWorker() {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return
  }

  if (import.meta.env.DEV) {
    // Skip SW registration in dev to avoid stale-cache confusion
    return
  }

  try {
    const registration = await navigator.serviceWorker.register(SW_PATH, {
      scope: '/field/',
    })

    registration.addEventListener('updatefound', () => {
      const newSw = registration.installing
      if (!newSw) return
      newSw.addEventListener('statechange', () => {
        if (newSw.state === 'installed' && navigator.serviceWorker.controller) {
          // New SW installed — prompt user to reload for updated job list
          if (typeof console !== 'undefined') {
            console.info('[SW] New service worker installed. Reload to apply updates.')
          }
        }
      })
    })
  } catch (err) {
    if (typeof console !== 'undefined') {
      console.warn('[SW] Service worker registration failed:', { error: String(err) })
    }
  }
}

/**
 * Returns true if the response came from the SW cache (degraded indicator).
 * @param {Response} response
 */
export function isStaleServiceWorkerResponse(response) {
  return response.headers.get('x-sw-stale') === 'true'
}
