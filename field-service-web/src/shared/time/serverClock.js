/**
 * serverClock — single shared server-clock skew module.
 *
 * Maintains the difference between server time and local clock so every
 * countdown widget uses a consistent, skew-corrected "server now" rather
 * than trusting the device clock.
 *
 * Design constraints (from WO-131):
 * - One shared 1 s tick driver; no per-instance intervals (INP budget).
 * - visibilitychange reconciliation: immediately re-corrects on tab resume.
 * - Works with positive and negative skew (device clock hours off).
 *
 * @module shared/time/serverClock
 */

/** Offset: skewMs = serverTimeMs - localTimeMs. */
let _skewMs = 0;

/** Tick subscribers — called once per second by the shared interval. */
const _tickSubs = new Set();

/** Single interval id. Null when no subscribers. */
let _intervalId = null;

// ---- Skew management ----------------------------------------------------

/**
 * Update the clock skew from a server Date header string.
 * Called by http.js on every successful API response.
 *
 * @param {string | null} serverDateHeader  Value of the HTTP Date header
 */
export function updateSkew(serverDateHeader) {
  if (!serverDateHeader) return;
  const serverMs = Date.parse(serverDateHeader);
  if (!Number.isFinite(serverMs)) return;
  _skewMs = serverMs - Date.now();
}

/**
 * Compute server-corrected "now" in milliseconds.
 * Use this instead of Date.now() wherever a deadline comparison is needed.
 *
 * @returns {number}
 */
export function serverNow() {
  return Date.now() + _skewMs;
}

/**
 * Returns the current skew offset in ms (positive = server ahead of device).
 * Exposed for testing.
 *
 * @returns {number}
 */
export function getSkewMs() {
  return _skewMs;
}

// ---- Shared tick driver -------------------------------------------------

/**
 * Subscribe to the shared 1-second tick.
 *
 * @param {() => void} fn  Callback invoked each tick
 * @returns {() => void}   Unsubscribe function
 */
export function subscribeToTick(fn) {
  _tickSubs.add(fn);
  _ensureInterval();
  return () => {
    _tickSubs.delete(fn);
    if (_tickSubs.size === 0) {
      clearInterval(_intervalId);
      _intervalId = null;
    }
  };
}

function _ensureInterval() {
  if (_intervalId !== null) return;
  _intervalId = setInterval(() => {
    _tickSubs.forEach((fn) => fn());
  }, 1000);
}

// ---- visibilitychange reconciliation ------------------------------------

/**
 * Register the visibility-change listener (call once at app startup).
 * On tab resume, re-fetches the server time and forces all tick subscribers
 * to fire immediately so countdowns correct without waiting for the next tick.
 *
 * This is important for laptops that wake from sleep — without reconciliation
 * the countdown would be hours stale until the next poll.
 *
 * @param {() => Promise<string | null>} fetchServerTime
 *   Async function that returns a server Date header string (or null).
 *   Pass a thin wrapper around the server clock endpoint or extract from
 *   a warm API response via the `Date` header captured in http.js.
 */
export function registerVisibilityHandler(fetchServerTime) {
  if (typeof document === 'undefined') return;
  document.addEventListener('visibilitychange', async () => {
    if (document.visibilityState !== 'visible') return;
    try {
      const dateHeader = await fetchServerTime();
      if (dateHeader) updateSkew(dateHeader);
    } catch (_) {
      // Reconciliation is best-effort; never throw on tab resume.
    }
    // Fire all tick subscribers immediately regardless of whether skew updated.
    _tickSubs.forEach((fn) => fn());
  });
}
