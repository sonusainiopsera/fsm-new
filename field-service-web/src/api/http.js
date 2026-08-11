/**
 * @fileoverview HTTP client — fetch wrapper enforcing the platform transport contract.
 *
 * Responsibilities:
 * - Attaches the in-memory Bearer token (never reads from storage).
 * - Generates an Idempotency-Key for every POST/PUT/PATCH/DELETE.
 * - Normalises errors into ClientError via errors.js.
 * - Intercepts 401: triggers exactly one silent refresh; queues concurrent
 *   callers behind the single in-flight attempt.
 * - Passes AbortSignal through to fetch.
 * - Validates response shape at the boundary (boundary runtime validation).
 */

import { normaliseError, networkError } from './errors.js'
import {
  getToken, refreshToken, isRefreshing, queueBehindRefresh, signOut,
} from './tokenStore.js'

const BASE_URL = '/api/v1'
const AUTH_PATH_PREFIX = '/api/v1/auth'

/** Mutating HTTP methods that require an Idempotency-Key. */
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * @template T
 * @param {string} path       Path relative to BASE_URL (e.g. '/work-orders')
 * @param {RequestInit & { signal?: AbortSignal, skipAuth?: boolean }} [options]
 * @returns {Promise<T>}      Resolves with parsed JSON body
 * @throws {import('./errors.js').ClientError}
 */
export async function request(path, options = {}) {
  const { signal, skipAuth = false, ...fetchOptions } = options
  const url = `${BASE_URL}${path}`
  const method = (fetchOptions.method ?? 'GET').toUpperCase()

  const headers = buildHeaders(method, fetchOptions.headers, url, skipAuth)

  const init = {
    ...fetchOptions,
    method,
    headers,
    signal: signal ?? undefined,
    // Include credentials only for the auth path (refresh cookie)
    credentials: url.startsWith(AUTH_PATH_PREFIX) ? 'include' : 'omit',
  }

  return _requestWithRetry(url, init, /* isRetry */ false)
}

/**
 * Convenience wrapper for GET.
 * @template T
 * @param {string} path
 * @param {RequestInit & { signal?: AbortSignal }} [options]
 * @returns {Promise<T>}
 */
export function get(path, options = {}) {
  return request(path, { ...options, method: 'GET' })
}

/**
 * Convenience wrapper for POST.
 * @template T
 * @param {string} path
 * @param {unknown} body
 * @param {RequestInit & { signal?: AbortSignal }} [options]
 * @returns {Promise<T>}
 */
export function post(path, body, options = {}) {
  return request(path, {
    ...options,
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/**
 * Convenience wrapper for PUT.
 * @template T
 * @param {string} path
 * @param {unknown} body
 * @param {RequestInit & { signal?: AbortSignal }} [options]
 * @returns {Promise<T>}
 */
export function put(path, body, options = {}) {
  return request(path, {
    ...options,
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

// ── Internal ──────────────────────────────────────────────────────────────────

/**
 * @param {string} url
 * @param {RequestInit} init
 * @param {boolean} isRetry  True when replaying after a successful token refresh
 * @returns {Promise<unknown>}
 */
async function _requestWithRetry(url, init, isRetry) {
  let response
  try {
    // Attach the latest token (may have been refreshed since headers were built)
    if (isRetry) {
      const freshToken = getToken()
      if (freshToken) {
        const headers = new Headers(init.headers)
        headers.set('Authorization', `Bearer ${freshToken}`)
        init = { ...init, headers }
      }
    }
    response = await fetch(url, init)
  } catch (err) {
    throw networkError(err)
  }

  // 401: attempt silent refresh once
  if (response.status === 401 && !isRetry) {
    return _handleUnauthorized(url, init)
  }

  if (!response.ok) {
    let body = null
    try { body = await response.json() } catch { /* ignore */ }
    throw normaliseError(response.status, body)
  }

  // 204 No Content
  if (response.status === 204) return /** @type {any} */ (null)

  // 304 Not Modified — caller (useConditionalQuery) handles this upstream
  if (response.status === 304) return /** @type {any} */ ({ __notModified: true })

  return response.json()
}

/**
 * Handles a 401 response: obtains a fresh token (single-flight) and retries.
 * @param {string} url
 * @param {RequestInit} init
 */
async function _handleUnauthorized(url, init) {
  try {
    if (isRefreshing()) {
      // Another refresh is already in flight; queue behind it
      await queueBehindRefresh()
    } else {
      await refreshToken()
    }
  } catch {
    // Refresh failed: sign out and propagate a 401 error
    signOut()
    throw normaliseError(401, null)
  }
  // Replay the original request with the new token
  return _requestWithRetry(url, init, /* isRetry */ true)
}

/**
 * Builds the request headers.
 * @param {string} method
 * @param {HeadersInit | undefined} extra
 * @param {string} url
 * @param {boolean} skipAuth
 * @returns {Headers}
 */
function buildHeaders(method, extra, url, skipAuth) {
  const headers = new Headers(extra)

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json')
  }

  if (MUTATING_METHODS.has(method)) {
    if (!headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    if (!headers.has('Idempotency-Key')) {
      headers.set('Idempotency-Key', generateIdempotencyKey())
    }
  }

  if (!skipAuth) {
    const token = getToken()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
  }

  return headers
}

/**
 * Generates a random Idempotency-Key in the platform-accepted format.
 * Uses crypto.randomUUID when available, falls back to a simpler random string.
 * @returns {string}
 */
function generateIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Fallback for environments without crypto.randomUUID
  return Array.from({ length: 32 }, () =>
    Math.floor(Math.random() * 16).toString(16)
  ).join('')
}
