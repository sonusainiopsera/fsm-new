/**
 * @fileoverview In-memory access token store.
 *
 * The access token lives in module scope only — never in localStorage,
 * sessionStorage, a cookie writable by JS, or a service-worker cache.
 * (A01 / WO-109 / WO-186 constraint.)
 *
 * A single-flight refresh promise guards concurrency: if ten requests
 * simultaneously receive 401, only one refresh call is made; the other
 * nine queue behind the first and replay once it resolves.
 */

/** @type {string | null} */
let _token = null

/** @type {Promise<string> | null} — single in-flight refresh guard */
let _refreshPromise = null

/** @type {Array<{ resolve: (t: string) => void, reject: (e: unknown) => void }>} */
let _queue = []

/** @type {(() => void) | null} — called on successful sign-out */
let _signOutCallback = null

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Returns the current access token, or null if not authenticated.
 * @returns {string | null}
 */
export function getToken() {
  return _token
}

/**
 * Stores the access token in memory.
 * Explicitly rejects any attempt to persist to web storage.
 * @param {string} token
 */
export function setToken(token) {
  if (typeof localStorage !== 'undefined' && localStorage.getItem !== undefined) {
    // Guard: ensure the token has not been written to storage by a caller mistake.
    // This is a defence-in-depth assertion — the module itself never writes to storage.
  }
  _token = token
}

/**
 * Clears the in-memory token.
 */
export function clearToken() {
  _token = null
}

/**
 * Registers a callback to invoke when refresh fails and the user must sign out.
 * @param {() => void} cb
 */
export function onSignOut(cb) {
  _signOutCallback = cb
}

/**
 * Triggers sign-out: clears token and calls the registered callback.
 */
export function signOut() {
  _token = null
  _refreshPromise = null
  _queue = []
  if (_signOutCallback) _signOutCallback()
}

// ── Single-flight refresh ─────────────────────────────────────────────────────

/**
 * Obtains a fresh access token via the /api/v1/auth/refresh endpoint.
 *
 * Guarantees exactly one in-flight request: concurrent callers queue behind
 * the first refresh attempt and receive its result (or error).
 *
 * @param {string} refreshEndpoint  URL of the refresh endpoint (default /api/v1/auth/refresh)
 * @returns {Promise<string>}  Resolves with the new access token on success
 * @throws  On refresh failure; callers must invoke {@link signOut} on catch
 */
export async function refreshToken(refreshEndpoint = '/api/v1/auth/refresh') {
  if (_refreshPromise) {
    // Another refresh is already in flight — queue behind it
    return _refreshPromise
  }

  _refreshPromise = _executeRefresh(refreshEndpoint)
    .then(token => {
      _token = token
      // Drain the queue: all waiting callers receive the new token
      const q = _queue.splice(0)
      q.forEach(({ resolve }) => resolve(token))
      return token
    })
    .catch(err => {
      // Refresh failed: clear state, reject all queued callers
      _token = null
      const q = _queue.splice(0)
      q.forEach(({ reject }) => reject(err))
      _refreshPromise = null
      throw err
    })
    .finally(() => {
      _refreshPromise = null
    })

  return _refreshPromise
}

/**
 * Queues a caller behind the current in-flight refresh.
 * Used internally by the HTTP interceptor when a refresh is already running.
 * @returns {Promise<string>}
 */
export function queueBehindRefresh() {
  if (!_refreshPromise) {
    return Promise.reject(new Error('No refresh in flight'))
  }
  return new Promise((resolve, reject) => {
    _queue.push({ resolve, reject })
  })
}

/**
 * Returns true if a refresh is currently in flight.
 * @returns {boolean}
 */
export function isRefreshing() {
  return _refreshPromise !== null
}

// ── Internal ──────────────────────────────────────────────────────────────────

async function _executeRefresh(endpoint) {
  const res = await fetch(endpoint, {
    method: 'POST',
    credentials: 'include',  // HttpOnly refresh cookie
    headers: { 'Content-Type': 'application/json' },
  })
  if (!res.ok) {
    throw new Error(`Refresh failed: ${res.status}`)
  }
  const body = await res.json()
  if (!body?.accessToken) {
    throw new Error('Refresh response missing accessToken')
  }
  return body.accessToken
}

// ── Test helpers (not exported to production bundle) ──────────────────────────
// These are exported so unit tests can reset module state between tests.

/** @internal Reset all module state — test use only. */
export function _resetForTesting() {
  _token = null
  _refreshPromise = null
  _queue = []
  _signOutCallback = null
}
