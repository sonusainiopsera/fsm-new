/**
 * @fileoverview Mock fetch handlers for the dashboard widget endpoint (WO-167).
 *
 * Endpoint: GET /api/v1/analytics/dashboard/widgets?metrics=...&window=...&segment=...
 *
 * Scenarios:
 *   'ok'        Normal 200 with all 11 metrics for THIRTY_DAYS (first call).
 *               Subsequent calls with matching ETag return 304.
 *   'degraded'  200 with two widgets, both degraded=true.
 *   'empty'     200 with zero widgets (sampleCount 0).
 *   'forbidden' 403 FORBIDDEN.
 *   'unavailable' 503 SERVICE_UNAVAILABLE.
 *   'rateLimited' 429 with Retry-After header.
 */

import widgetsFixture  from '../fixtures/dashboard/widgets-30d.json'
import degradedFixture from '../fixtures/dashboard/widgets-degraded.json'

const BASE = '/api/v1'
const WIDGETS_PATH_RE = /\/api\/v1\/analytics\/dashboard\/widgets(\?.*)?$/

const CURRENT_ETAG = '"dashboard-etag-v1"'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonOk(body, extraHeaders = {}) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: {
      get: (h) => {
        const k = h.toLowerCase()
        if (k === 'content-type') return 'application/json'
        return extraHeaders[k] ?? null
      },
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    clone() { return jsonOk(body, extraHeaders) },
  })
}

function notModified() {
  return Promise.resolve({
    ok: false,
    status: 304,
    headers: { get: (h) => h.toLowerCase() === 'etag' ? CURRENT_ETAG : null },
    json: () => Promise.resolve(null),
    text: () => Promise.resolve(''),
    clone() { return notModified() },
  })
}

function jsonError(status, body, extraHeaders = {}) {
  return Promise.resolve({
    ok: false,
    status,
    headers: {
      get: (h) => {
        const k = h.toLowerCase()
        if (k === 'content-type') return 'application/json'
        return extraHeaders[k] ?? null
      },
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    clone() { return jsonError(status, body, extraHeaders) },
  })
}

// ── Fixtures ──────────────────────────────────────────────────────────────────

const ERROR_403 = { code: 'FORBIDDEN', message: 'Access denied.', fieldErrors: [], traceId: 'trace-403-dash' }
const ERROR_503 = { code: 'SERVICE_UNAVAILABLE', message: 'Analytics read model temporarily unavailable.', fieldErrors: [], traceId: 'trace-503-dash' }
const ERROR_429 = { code: 'RATE_LIMITED', message: 'Too many requests.', fieldErrors: [], traceId: 'trace-429-dash' }
const EMPTY_RESPONSE = { data: [], page: { number: 0, size: 0, totalElements: 0, totalPages: 0 } }

// ── Handler factory ───────────────────────────────────────────────────────────

/**
 * Creates a mock fetch function for the dashboard widget endpoint.
 *
 * @param {{
 *   scenario?: 'ok' | 'degraded' | 'empty' | 'forbidden' | 'unavailable' | 'rateLimited',
 *   etagEnabled?: boolean
 * }} [opts]
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function createDashboardFetch({ scenario = 'ok', etagEnabled = true } = {}) {
  return async function mockFetch(url, init = {}) {
    if (!WIDGETS_PATH_RE.test(url)) {
      return jsonError(501, { code: 'NOT_MOCKED', message: `No mock for ${url}`, fieldErrors: [], traceId: '' })
    }

    switch (scenario) {
      case 'forbidden':
        return jsonError(403, ERROR_403)
      case 'unavailable':
        return jsonError(503, ERROR_503)
      case 'rateLimited':
        return jsonError(429, ERROR_429, { 'retry-after': '30' })
      case 'empty':
        return jsonOk(EMPTY_RESPONSE, { etag: CURRENT_ETAG, 'cache-control': 'max-age=30' })
      case 'degraded':
        return jsonOk(degradedFixture, { etag: CURRENT_ETAG, 'cache-control': 'max-age=30' })
      case 'ok':
      default: {
        const headers = new Headers(init?.headers ?? {})
        const clientEtag = headers.get('If-None-Match')
        if (etagEnabled && clientEtag === CURRENT_ETAG) {
          return notModified()
        }
        return jsonOk(widgetsFixture, { etag: CURRENT_ETAG, 'cache-control': 'max-age=30' })
      }
    }
  }
}

export { widgetsFixture, degradedFixture, CURRENT_ETAG }
