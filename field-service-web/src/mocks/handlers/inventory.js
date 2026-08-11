/**
 * @fileoverview Fetch-intercept mock handlers for inventory API endpoints.
 *
 * Pattern: each handler factory returns a function compatible with
 * vi.stubGlobal('fetch', handler) for use in Vitest tests.
 *
 * Endpoints covered:
 *   GET  /api/v1/inventory/parts/search
 *   GET  /api/v1/inventory/stock
 *   GET  /api/v1/inventory/movements
 *   GET  /api/v1/inventory/alerts
 *   POST /api/v1/work-orders/:id/parts/consume
 *   POST /api/v1/work-orders/:id/parts/return
 *   GET  /api/v1/work-orders/:id/parts
 *   POST /api/v1/work-orders/:id/transitions
 */

import stockPositionsFixture from '../fixtures/inventory/stock-positions.json'
import movementsFixture from '../fixtures/inventory/movements.json'
import alertsFixture from '../fixtures/inventory/alerts.json'
import partsSearchFixture from '../fixtures/inventory/parts-search.json'
import staleAsOfFixture from '../fixtures/inventory/stale-as-of.json'
import insufficientStockFixture from '../fixtures/inventory/insufficient-stock.json'

const BASE = '/api/v1'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonOk(body, status = 200) {
  return Promise.resolve({
    ok: true,
    status,
    headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  })
}

function jsonError(status, body) {
  return Promise.resolve({
    ok: false,
    status,
    headers: { get: () => null },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  })
}

function noContent() {
  return Promise.resolve({ ok: true, status: 204, headers: { get: () => null }, json: () => Promise.resolve(null) })
}

// ── Handler builders ──────────────────────────────────────────────────────────

/**
 * Happy-path handler: stock positions list.
 * @param {{ stale?: boolean }} options
 */
export function stockPositionsHandler({ stale = false } = {}) {
  const fixture = stale ? staleAsOfFixture : stockPositionsFixture
  return (url) => {
    if (url.includes(`${BASE}/inventory/stock`)) return jsonOk(fixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Happy-path handler: movements list.
 */
export function movementsHandler() {
  return (url) => {
    if (url.includes(`${BASE}/inventory/movements`)) return jsonOk(movementsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Happy-path handler: alerts list.
 */
export function alertsHandler() {
  return (url) => {
    if (url.includes(`${BASE}/inventory/alerts`)) return jsonOk(alertsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Happy-path handler: parts search.
 * @param {{ empty?: boolean }} options
 */
export function partsSearchHandler({ empty = false } = {}) {
  const fixture = empty
    ? { data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }, links: {} }
    : partsSearchFixture
  return (url) => {
    if (url.includes(`${BASE}/inventory/parts/search`)) return jsonOk(fixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Returns a handler that resolves consumption requests with a 200 success.
 */
export function consumeSuccessHandler() {
  return (url, opts) => {
    if (url.includes('/parts/consume') && opts?.method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({
        workOrderId: body.workOrderId ?? 'wo-001',
        consumedLines: body.lines ?? [],
        returnedLines: [],
      }, 200)
    }
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Returns a handler that rejects consumption with 422 INSUFFICIENT_STOCK.
 */
export function consumeInsufficientStockHandler() {
  return (url, opts) => {
    if (url.includes('/parts/consume') && opts?.method === 'POST') {
      return jsonError(422, insufficientStockFixture)
    }
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Returns a handler that simulates a network failure on consumption.
 */
export function consumeNetworkErrorHandler() {
  return (url, opts) => {
    if (url.includes('/parts/consume') && opts?.method === 'POST') {
      return Promise.reject(new TypeError('Failed to fetch'))
    }
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Returns a handler for returns requests (happy path).
 */
export function returnSuccessHandler() {
  return (url, opts) => {
    if (url.includes('/parts/return') && opts?.method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({
        workOrderId: body.workOrderId ?? 'wo-001',
        consumedLines: [],
        returnedLines: body.lines ?? [],
      }, 200)
    }
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Returns a handler for the awaiting-parts hold transition (happy path).
 */
export function holdTransitionHandler() {
  return (url, opts) => {
    if (url.includes('/transitions') && opts?.method === 'POST') {
      return jsonOk({ state: 'ON_HOLD', holdReasonCode: 'AWAITING_PARTS' }, 200)
    }
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * A combined handler that routes across multiple inventory endpoints.
 * Accepts an optional overrides map: endpoint key → handler.
 *
 * @param {{ errorCode?: number }} options
 */
export function inventoryHandlers({ errorCode } = {}) {
  return async (url, opts = {}) => {
    if (errorCode) {
      return jsonError(errorCode, { code: 'ERROR', message: `HTTP ${errorCode}`, fieldErrors: [] })
    }
    if (url.includes(`${BASE}/inventory/parts/search`)) return jsonOk(partsSearchFixture)
    if (url.includes(`${BASE}/inventory/stock`)) return jsonOk(stockPositionsFixture)
    if (url.includes(`${BASE}/inventory/movements`)) return jsonOk(movementsFixture)
    if (url.includes(`${BASE}/inventory/alerts`)) return jsonOk(alertsFixture)
    if (url.includes('/parts/consume') && opts.method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ workOrderId: body.workOrderId ?? 'wo-001', consumedLines: body.lines ?? [], returnedLines: [] })
    }
    if (url.includes('/parts/return') && opts.method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ workOrderId: body.workOrderId ?? 'wo-001', consumedLines: [], returnedLines: body.lines ?? [] })
    }
    if (url.includes('/transitions') && opts.method === 'POST') {
      return jsonOk({ state: 'ON_HOLD' })
    }
    if (url.includes('/parts') && opts.method === 'GET') {
      return jsonOk({ workOrderId: 'wo-001', consumedLines: [], returnedLines: [] })
    }
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}
