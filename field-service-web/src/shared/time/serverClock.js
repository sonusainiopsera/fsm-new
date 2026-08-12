/**
 * @fileoverview Server clock — single skew-corrected time source for all countdowns.
 *
 * Captures the difference between the server's reported time and the local
 * clock once per API response, then exposes serverNow() so countdown
 * components never derive remaining time from the local clock alone.
 *
 * One shared setInterval drives all subscribers (AC-7 constraint: one tick
 * driver, not one per countdown instance). When the last subscriber
 * unsubscribes the interval is cleared to avoid a leak.
 *
 * visibilitychange: when the browser tab becomes visible again the tick fires
 * immediately so a laptop resumed from sleep corrects at once rather than
 * waiting up to one second.
 *
 * @module shared/time/serverClock
 */

// ── Module-level state ────────────────────────────────────────────────────────

/** Estimated offset: serverTime - localTime in milliseconds. */
let _skewMs = 0

/** Active subscriber callbacks. */
const _listeners = new Set()

/** Single shared interval ID. */
let _intervalId = null

const TICK_MS = 1000

// ── Core API ──────────────────────────────────────────────────────────────────

/**
 * Updates the skew offset from a server-provided timestamp.
 * Call this whenever a server response carries a reliable Date or timestamp.
 *
 * @param {number} serverTimestampMs  Unix epoch milliseconds from the server
 */
export function captureSkew(serverTimestampMs) {
  _skewMs = serverTimestampMs - Date.now()
}

/**
 * Returns the current server-estimated time in milliseconds since epoch.
 * Safe to call at any frequency; does not trigger renders.
 *
 * @returns {number}
 */
export function serverNow() {
  return Date.now() + _skewMs
}

/**
 * Returns the current skew offset in milliseconds (positive = server ahead).
 * Useful for unit tests.
 *
 * @returns {number}
 */
export function getSkewMs() {
  return _skewMs
}

/**
 * Resets internal state. For test isolation only.
 */
export function _resetForTests() {
  _skewMs = 0
  _listeners.clear()
  if (_intervalId != null) {
    clearInterval(_intervalId)
    _intervalId = null
  }
}

// ── Shared tick driver ────────────────────────────────────────────────────────

function _tick() {
  const now = serverNow()
  _listeners.forEach(fn => {
    try { fn(now) } catch { /* individual subscriber errors must not kill the driver */ }
  })
}

/**
 * Subscribes a callback to the shared one-second tick.
 * The callback receives the current server timestamp on each tick.
 *
 * Returns an unsubscribe function. Call it in a useEffect cleanup.
 *
 * @param {(serverNowMs: number) => void} fn
 * @returns {() => void} unsubscribe
 */
export function subscribe(fn) {
  _listeners.add(fn)

  // Start the shared interval when the first subscriber registers
  if (_intervalId == null) {
    _intervalId = setInterval(_tick, TICK_MS)
  }

  return function unsubscribe() {
    _listeners.delete(fn)
    // Stop the interval when the last subscriber leaves
    if (_listeners.size === 0 && _intervalId != null) {
      clearInterval(_intervalId)
      _intervalId = null
    }
  }
}

// ── visibilitychange reconciliation ──────────────────────────────────────────

if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      // Fire immediately so a resumed-from-sleep tab corrects at once
      _tick()
    }
  })
}
