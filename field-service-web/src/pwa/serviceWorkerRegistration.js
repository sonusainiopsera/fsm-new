/**
 * Technician PWA service worker registration.
 *
 * Only activates in production builds (or when VITE_ENABLE_SW=true in dev) so
 * hot-module replacement works unaffected during development.
 *
 * Update strategy: a new SW waiting in the `installed` state is NOT forced via
 * skipWaiting(). Instead, the registration calls `onUpdateAvailable(applyFn)`
 * so the shell can surface a non-disruptive update prompt. The technician
 * taps "Update now" and the shell calls `applyFn()`, which posts SKIP_WAITING
 * to the waiting SW. The page reloads on `controllerchange`.
 *
 * Logout: call `purgeTechnicianCaches()` to post PURGE_CACHES to the active
 * controller — the SW deletes all fsvc-tech-* runtime caches so no previous
 * technician's jobs remain readable.
 */

const SW_URL   = '/technician-sw.js';
const SW_SCOPE = '/technician/';

/**
 * @param {{ onUpdateAvailable?: (applyUpdate: () => void) => void }} [opts]
 * @returns {Promise<ServiceWorkerRegistration | null>}
 */
export async function registerTechnicianServiceWorker(opts = {}) {
  if (!('serviceWorker' in navigator)) return null;

  // Guard: only register in production or explicit opt-in via env flag
  if (process.env.NODE_ENV !== 'production' && !process.env.VITE_ENABLE_SW) {
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.register(SW_URL, { scope: SW_SCOPE });

    registration.addEventListener('updatefound', () => {
      const incoming = registration.installing;
      if (!incoming) return;

      incoming.addEventListener('statechange', () => {
        if (incoming.state === 'installed' && navigator.serviceWorker.controller) {
          // A new version is waiting — surface the update prompt
          opts.onUpdateAvailable?.(() => {
            // Reload on controller swap (called after skipWaiting in SW)
            navigator.serviceWorker.addEventListener('controllerchange', () => {
              window.location.reload();
            }, { once: true });
            incoming.postMessage({ type: 'SKIP_WAITING' });
          });
        }
      });
    });

    return registration;
  } catch (err) {
    if (process.env.NODE_ENV !== 'production') {
      console.warn('[TechnicianSW] Registration failed:', err);
    }
    return null;
  }
}

/**
 * Posts PURGE_CACHES to the active service worker.
 * Call on logout to ensure no previous technician's job cache is accessible.
 */
export function purgeTechnicianCaches() {
  if (navigator.serviceWorker?.controller) {
    navigator.serviceWorker.controller.postMessage({ type: 'PURGE_CACHES' });
  }
}

/**
 * Unregisters all service workers for this origin.
 * Used in tests and explicit reset flows.
 */
export async function unregisterTechnicianServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  const registrations = await navigator.serviceWorker.getRegistrations();
  await Promise.all(registrations.map((r) => r.unregister()));
}
