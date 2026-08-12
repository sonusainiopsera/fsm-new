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

// ── Job detail handlers (WO-156 / WO-157) ────────────────────────────────────

import jobDetailAssigned from '../fixtures/technician/job-detail-assigned.json'
import jobDetailEnRoute from '../fixtures/technician/job-detail-en-route.json'
import jobDetailInProgress from '../fixtures/technician/job-detail-in-progress.json'
import jobDetailOnHold from '../fixtures/technician/job-detail-on-hold.json'

const JOB_DETAIL_BY_STATE = {
  'wo-tech-001': jobDetailAssigned,
  'wo-tech-002': jobDetailEnRoute,
  'wo-tech-003': jobDetailInProgress,
  'wo-tech-004': jobDetailOnHold,
}

const JOB_DETAIL_PATH_RE = /\/api\/v1\/technicians\/me\/work-orders\/([^/?]+)$/

/**
 * Handler for GET /api/v1/technicians/me/work-orders/{id} (job detail).
 */
export function jobDetailHandler(fixture) {
  return function mockFetch(url, options = {}) {
    const match = url.match(JOB_DETAIL_PATH_RE)
    if (!match) return fetch(url, options)
    const id = match[1]
    const body = fixture ?? JOB_DETAIL_BY_STATE[id] ?? jobDetailInProgress
    return jsonResponse(body)
  }
}

// ── Asset service history handler (WO-156) ───────────────────────────────────

import assetHistoryFixture from '../fixtures/technician/asset-history.json'

const ASSET_HISTORY_PATH_RE = /\/api\/v1\/assets\/([^/]+)\/service-history$/

/**
 * Handler for GET /api/v1/assets/{assetId}/service-history.
 */
export function assetHistoryHandler(fixture) {
  return function mockFetch(url, options = {}) {
    if (!ASSET_HISTORY_PATH_RE.test(url)) return fetch(url, options)
    return jsonResponse(fixture ?? assetHistoryFixture)
  }
}

// ── Vehicle stock handlers (WO-157) ──────────────────────────────────────────

import vehicleStock from '../fixtures/technician/vehicle-stock.json'
import vehicleStockEmpty from '../fixtures/technician/vehicle-stock-empty.json'

const STOCK_PATH = '/api/v1/technicians/me/stock'

/**
 * Handler for GET /api/v1/technicians/me/stock — returns full stock fixture.
 */
export function vehicleStockHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.includes(STOCK_PATH)) return fetch(url, options)
    return jsonResponse(vehicleStock)
  }
}

/**
 * Handler for GET /api/v1/technicians/me/stock — returns empty stock.
 */
export function vehicleStockEmptyHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.includes(STOCK_PATH)) return fetch(url, options)
    return jsonResponse(vehicleStockEmpty)
  }
}

// ── Labour and parts-consumption handlers (WO-157) ───────────────────────────

const LABOUR_PATH_RE = /\/api\/v1\/work-orders\/([^/]+)\/labour$/
const PARTS_CONSUMPTION_PATH_RE = /\/api\/v1\/work-orders\/([^/]+)\/parts-consumption$/

/**
 * Handler for POST /api/v1/work-orders/{id}/labour — 201 Created.
 */
export function labourSuccessHandler() {
  return function mockFetch(url, options = {}) {
    if (!LABOUR_PATH_RE.test(url)) return fetch(url, options)
    const body = JSON.parse(options.body ?? '{}')
    return Promise.resolve({
      ok: true,
      status: 201,
      headers: {
        get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null,
        forEach: () => {},
      },
      json: () => Promise.resolve({
        id: 'labour-001',
        workOrderId: LABOUR_PATH_RE.exec(url)?.[1],
        minutes: body.durationMinutes ?? 60,
        workDate: new Date().toISOString(),
        note: body.note ?? null,
      }),
      arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
      clone() { return this },
    })
  }
}

/**
 * Handler for POST /api/v1/work-orders/{id}/parts-consumption — 200 OK.
 */
export function partsConsumptionSuccessHandler() {
  return function mockFetch(url, options = {}) {
    if (!PARTS_CONSUMPTION_PATH_RE.test(url)) return fetch(url, options)
    return jsonResponse({ consumed: true, lines: [] })
  }
}

/**
 * Handler for POST /api/v1/work-orders/{id}/parts-consumption — 422 INSUFFICIENT_STOCK.
 */
export function partsConsumptionShortfallHandler(shortfallLines) {
  return function mockFetch(url, options = {}) {
    if (!PARTS_CONSUMPTION_PATH_RE.test(url)) return fetch(url, options)
    const lines = shortfallLines ?? [
      {
        field: 'lines[0].quantity',
        message: 'requested 3, available 0',
      },
    ]
    return errorResponse(422, {
      code: 'INSUFFICIENT_STOCK',
      message: 'Insufficient stock for one or more requested lines.',
      fieldErrors: lines,
      traceId: 'trace-422-parts',
    })
  }
}

/**
 * Handler for POST /api/v1/work-orders/{id}/transitions — 422 GUARD_FAILED (no labour time).
 */
export function completeGuardFailedHandler() {
  return function mockFetch(url, options = {}) {
    if (!url.match(/\/transitions$/)) return fetch(url, options)
    const body = JSON.parse(options?.body ?? '{}')
    if (body.event !== 'COMPLETE') return fetch(url, options)
    return errorResponse(422, {
      code: 'GUARD_FAILED',
      message: 'Labour time must be recorded before completion.',
      fieldErrors: [],
      traceId: 'trace-422-guard',
    })
  }
}
