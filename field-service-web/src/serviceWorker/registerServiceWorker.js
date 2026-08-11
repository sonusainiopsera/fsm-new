/**
 * Registers the field surface service worker.
 *
 * Called exclusively from the field surface entry (src/surfaces/field/index.jsx)
 * so the service worker is never registered for dispatcher, operations, or
 * portal surfaces. The cache scope is limited to /api/v1/work-orders GET
 * responses — no other endpoints are ever cached.
 *
 * Access tokens are never written to the service worker cache. The SW
 * is read-only: it stores GET responses only and ignores all mutation
 * requests (POST, PUT, PATCH, DELETE).
 */
export async function registerFieldServiceWorker() {
  if (!('serviceWorker' in navigator)) return;

  try {
    const registration = await navigator.serviceWorker.register(
      '/fieldServiceWorker.js',
      { scope: '/field/' }
    );

    if (process.env.NODE_ENV !== 'production') {
      console.info('[ServiceWorker] Field worker registered:', registration.scope);
    }

    registration.addEventListener('updatefound', () => {
      const installing = registration.installing;
      if (!installing) return;
      installing.addEventListener('statechange', () => {
        if (installing.state === 'installed' && navigator.serviceWorker.controller) {
          if (process.env.NODE_ENV !== 'production') {
            console.info('[ServiceWorker] New field worker available — reload to activate.');
          }
        }
      });
    });
  } catch (err) {
    if (process.env.NODE_ENV !== 'production') {
      console.warn('[ServiceWorker] Field worker registration failed:', err);
    }
  }
}

/**
 * Unregisters all service workers for the current scope.
 * Used during development and testing.
 */
export async function unregisterServiceWorkers() {
  if (!('serviceWorker' in navigator)) return;
  const registrations = await navigator.serviceWorker.getRegistrations();
  await Promise.all(registrations.map((r) => r.unregister()));
}
