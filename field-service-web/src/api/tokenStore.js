/**
 * In-memory access token store.
 *
 * Security contract:
 * - Token lives in module scope only — NEVER in localStorage, sessionStorage,
 *   a URL, or a service-worker cache.
 * - A single-flight refresh promise prevents concurrent refresh calls.
 * - Subscribers are notified on every set/clear so queued requests can replay.
 */

/** @type {string | null} */
let _accessToken = null;

/** @type {Promise<string> | null} Single-flight refresh guard */
let _refreshPromise = null;

/** @type {Array<(token: string | null) => void>} */
const _subscribers = [];

export const tokenStore = {
  /**
   * Returns the current in-memory access token, or null.
   * @returns {string | null}
   */
  get() {
    return _accessToken;
  },

  /**
   * Sets the access token in memory only.
   * @param {string} token
   */
  set(token) {
    _accessToken = token;
    _notify(token);
  },

  /**
   * Clears the in-memory token.
   */
  clear() {
    _accessToken = null;
    _notify(null);
  },

  /**
   * Subscribes to token changes (set and clear).
   * Used to replay queued requests after a successful refresh.
   * @param {(token: string | null) => void} callback
   * @returns {() => void} unsubscribe function
   */
  subscribe(callback) {
    _subscribers.push(callback);
    return () => {
      const i = _subscribers.indexOf(callback);
      if (i !== -1) _subscribers.splice(i, 1);
    };
  },

  /**
   * Single-flight token refresh.
   *
   * If a refresh is already in flight, returns the existing promise so
   * concurrent 401 failures share one network call. On success the new token
   * is stored and subscribers are notified. On failure the token is cleared.
   *
   * @param {() => Promise<string>} refreshFn
   * @returns {Promise<string>}
   */
  refresh(refreshFn) {
    if (_refreshPromise) return _refreshPromise;

    _refreshPromise = refreshFn()
      .then((newToken) => {
        tokenStore.set(newToken);
        return newToken;
      })
      .catch((err) => {
        tokenStore.clear();
        throw err;
      })
      .finally(() => {
        _refreshPromise = null;
      });

    return _refreshPromise;
  },

  /**
   * Returns true while a refresh call is in flight.
   * @returns {boolean}
   */
  isRefreshing() {
    return _refreshPromise !== null;
  },

  /**
   * Resets all state. Call after sign-out or a failed refresh.
   */
  reset() {
    _accessToken = null;
    _refreshPromise = null;
    _subscribers.length = 0;
  },
};

/**
 * @param {string | null} token
 */
function _notify(token) {
  for (const cb of [..._subscribers]) {
    try { cb(token); } catch (_) {}
  }
}
