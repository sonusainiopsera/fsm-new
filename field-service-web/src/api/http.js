/**
 * HTTP client — fetch wrapper for the platform API.
 *
 * Responsibilities:
 * - Base URL /api/v1
 * - Attach in-memory access token (never from localStorage/sessionStorage)
 * - credentials: include only for /auth paths (HttpOnly refresh cookie)
 * - Idempotency-Key on POST/PUT/PATCH/DELETE
 * - Single in-flight silent 401 refresh with queued request replay
 * - AbortSignal forwarding
 * - Boundary runtime validation of the response envelope
 * - ClientError on every non-2xx response
 */

import { tokenStore } from './tokenStore.js';
import { ClientError, normalizeError, networkError } from './errors.js';

const BASE = '/api/v1';
const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * Core fetch wrapper.
 *
 * @param {string} path   Path under /api/v1, e.g. '/work-orders'
 * @param {RequestInit & { _isRetry?: boolean }} [options]
 * @returns {Promise<unknown>}
 */
export async function apiFetch(path, options = {}) {
  const { _isRetry = false, ...rest } = options;
  const method = (rest.method ?? 'GET').toUpperCase();
  const isAuthPath = path.startsWith('/auth');

  const headers = new Headers(rest.headers ?? {});
  headers.set('Accept', 'application/json');
  if (rest.body !== undefined && rest.body !== null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const token = tokenStore.get();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  // Idempotency-Key for all mutating verbs, unless the caller already set one
  if (MUTATING.has(method) && !headers.has('Idempotency-Key')) {
    headers.set('Idempotency-Key', crypto.randomUUID());
  }

  // If-None-Match for conditional GET (ETag polling)
  if (rest.headers && rest.headers['If-None-Match']) {
    headers.set('If-None-Match', rest.headers['If-None-Match']);
  }

  const fetchOptions = {
    ...rest,
    method,
    headers,
    credentials: isAuthPath ? 'include' : 'same-origin',
  };
  delete fetchOptions._isRetry;

  let response;
  try {
    response = await fetch(`${BASE}${path}`, fetchOptions);
  } catch (err) {
    throw networkError(err);
  }

  // 304 Not Modified — caller should use cached data
  if (response.status === 304) {
    return { _304: true, etag: response.headers.get('ETag') };
  }

  // Single-flight 401 silent refresh (not on auth paths to avoid refresh loops)
  if (response.status === 401 && !isAuthPath && !_isRetry) {
    return _handleUnauthorized(path, options);
  }

  return _parseResponse(response);
}

/**
 * Initiates a single-flight token refresh, then replays the original request.
 * Concurrent 401 callers share the same refresh promise.
 */
async function _handleUnauthorized(originalPath, originalOptions) {
  await tokenStore.refresh(_doRefresh);
  // Replay with _isRetry so we don't loop
  return apiFetch(originalPath, { ...originalOptions, _isRetry: true });
}

/**
 * Calls POST /api/v1/auth/refresh with credentials to renew the access token
 * from the HttpOnly refresh cookie.
 * @returns {Promise<string>} new access token
 */
async function _doRefresh() {
  const response = await fetch(`${BASE}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    let body = null;
    try { body = await response.json(); } catch (_) {}
    throw normalizeError(response, body);
  }

  const data = await response.json();
  if (typeof data?.accessToken !== 'string') {
    throw new ClientError(401, 'INVALID_REFRESH_RESPONSE', 'Refresh response missing accessToken');
  }
  return data.accessToken;
}

/**
 * Parses the response body, validates the envelope, and returns the result.
 * Throws ClientError for non-2xx. Returns null for 204.
 *
 * @param {Response} response
 * @returns {Promise<unknown>}
 */
async function _parseResponse(response) {
  if (response.status === 204) return null;

  const contentType = response.headers.get('Content-Type') ?? '';
  let body = null;

  if (contentType.includes('application/json')) {
    try {
      body = await response.json();
    } catch (_) {
      throw new ClientError(
        response.status,
        'INVALID_RESPONSE',
        'Response did not match expected shape — failed JSON parse',
      );
    }
  }

  if (!response.ok) {
    throw normalizeError(response, body);
  }

  _validateEnvelope(body, response.status);

  // Attach ETag from response header if present
  const etag = response.headers.get('ETag');
  if (etag && body && typeof body === 'object') {
    body = { ...body, _etag: etag };
  }

  return body;
}

/**
 * Boundary runtime validation. Rejects responses whose top-level shape
 * does not match the expected envelope. Never exposes provider detail.
 *
 * @param {unknown} body
 * @param {number} status
 */
function _validateEnvelope(body, status) {
  if (body === null || body === undefined) return;
  if (typeof body !== 'object' || Array.isArray(body)) {
    throw new ClientError(
      status,
      'INVALID_RESPONSE',
      'Response shape failed boundary validation',
    );
  }
}

/** Convenience wrappers */
export const get   = (path, opts = {}) => apiFetch(path, { ...opts, method: 'GET'  });
export const post  = (path, body, opts = {}) => apiFetch(path, { ...opts, method: 'POST',  body: JSON.stringify(body) });
export const put   = (path, body, opts = {}) => apiFetch(path, { ...opts, method: 'PUT',   body: JSON.stringify(body) });
export const patch = (path, body, opts = {}) => apiFetch(path, { ...opts, method: 'PATCH', body: JSON.stringify(body) });
export const del   = (path, opts = {}) => apiFetch(path, { ...opts, method: 'DELETE' });
