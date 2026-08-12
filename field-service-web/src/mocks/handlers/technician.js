/**
 * @fileoverview MSW fetch-intercept handlers for the technician day-list endpoint.
 *
 * Scenarios covered:
 *   GET  /api/v1/technicians/me/work-orders  → 200 with day-list fixture
 *   GET  /api/v1/technicians/me/work-orders  → 304 Not Modified (ETag match)
 *   GET  /api/v1/technicians/me/work-orders  → 401 Unauthorized
 *   GET  /api/v1/technicians/me/work-orders  → 503 Service Unavailable (degraded)
 *   Network failure (no response)
 *
 * Pattern: returns plain fetch-compatible mock functions used by vi.stubGlobal.
 * All handlers are pure factory functions that do not import from the test harness.
 */
import dayListFixture from '../fixtures/technician/day-list.json'

const BASE = '/api/v1'
const DAY_LIST_PATH = `${BASE}/technicians/me/work-orders`
const ETAG = '"day-list-v1"'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonResponse(body, status = 200, headers = {}) {
  const allHeaders = {
    'content-type': 'application/json',
    etag: ETAG,
    ...headers,
  }
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (h) => allHeaders[h.toLowerCase()] ?? null,
      forEach: (fn) => Object.entries(allHeaders).forEach(([k, v]) => fn(v, k)),
    },
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(
      new TextEncoder().encode(JSON.stringify(body)).buffer
    ),
    clone() { return jsonResponse(body, status, headers) },
  })
}

function notModifiedResponse() {
  return Promise.resolve({
    ok: false,
    status: 304,
    headers: {
      get: (h) => h.toLowerCase() === 'etag' ? ETAG : null,
      forEach: () => {},
    },
    json: () => Promise.reject(new Error('No body on 304')),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    clone() { return notModifiedResponse() },
  })
}

function errorResponse(status, body) {
  return Promise.resolve({
    ok: false,
    status,
    headers: {
      get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null,
      forEach: () => {},
    },
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(
      new TextEncoder().encode(JSON.stringify(body)).buffer
    ),
    clone() { return errorResponse(status, body) },
  })
}

// ── Handler factories ─────────────────────────────────────────────────────────

/**
 * Handler that returns the day-list fixture (200 OK).
 * Respects If-None-Match to simulate 304 Not Modified.
 */
export function dayListSuccessHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.includes(DAY_LIST_PATH)) return fetch(url, options)
    const ifNoneMatch = options.headers?.['If-None-Match'] ?? options.headers?.['if-none-match']
    if (ifNoneMatch === ETAG) {
      return notModifiedResponse()
    }
    return jsonResponse(dayListFixture)
  }
}

/**
 * Handler that returns 401 Unauthorized.
 */
export function dayListUnauthorizedHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.includes(DAY_LIST_PATH)) return fetch(url, options)
    return errorResponse(401, {
      code: 'UNAUTHENTICATED',
      message: 'Authentication required.',
      fieldErrors: [],
      traceId: 'trace-401-tech',
    })
  }
}

/**
 * Handler that returns 503 Service Unavailable.
 */
export function dayListDegradedHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.includes(DAY_LIST_PATH)) return fetch(url, options)
    return errorResponse(503, {
      code: 'PROVIDER_DEGRADED',
      message: 'Service temporarily unavailable.',
      fieldErrors: [],
      traceId: 'trace-503-tech',
    })
  }
}

/**
 * Handler that simulates a network failure (no response).
 */
export function dayListNetworkFailureHandler() {
  return function mockFetch(url) {
    if (!url.includes(DAY_LIST_PATH)) return fetch(url)
    return Promise.reject(new TypeError('Network request failed'))
  }
}

/**
 * Handler that simulates an empty day list (200 OK, no jobs).
 */
export function dayListEmptyHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.includes(DAY_LIST_PATH)) return fetch(url, options)
    return jsonResponse({ jobs: [], date: '2026-08-15', totalAssigned: 0 })
  }
}

export { dayListFixture, ETAG as DAY_LIST_ETAG }
