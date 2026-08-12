/**
 * @fileoverview Fetch-intercept mock handlers for portal endpoints.
 *
 * Endpoints covered:
 *   GET  /api/v1/portal/sites                              (customer's own sites)
 *   GET  /api/v1/portal/sites/:siteId/assets              (assets at a site)
 *   POST /api/v1/portal/service-requests                  (submission)
 *   GET  /api/v1/portal/service-requests/:id/status       (status polling with ETag)
 */

import sitesFixture from '../fixtures/portal/portal-sites.json'
import assetsFixture from '../fixtures/portal/portal-site-assets.json'
import createdFixture from '../fixtures/portal/portal-submit-created.json'
import submit400Fixture from '../fixtures/portal/portal-submit-400.json'
import submit429Fixture from '../fixtures/portal/portal-submit-429.json'
import statusNewFixture from '../fixtures/portal/portal-status-new.json'
import statusAssignedFixture from '../fixtures/portal/portal-status-assigned.json'
import statusInProgressFixture from '../fixtures/portal/portal-status-in-progress.json'
import statusCompletedFixture from '../fixtures/portal/portal-status-completed.json'
import statusDegradedFixture from '../fixtures/portal/portal-status-degraded.json'

const BASE = '/api/v1/portal'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonOk(body, { etag, retryAfter } = {}) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: {
      get: (h) => {
        const lower = h.toLowerCase()
        if (lower === 'content-type') return 'application/json'
        if (lower === 'etag' && etag) return etag
        if (lower === 'retry-after' && retryAfter != null) return String(retryAfter)
        return null
      },
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  })
}

function jsonCreated(body) {
  return Promise.resolve({
    ok: true,
    status: 201,
    headers: {
      get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null,
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  })
}

function notModified() {
  return Promise.resolve({
    ok: false,
    status: 304,
    headers: { get: () => null },
    json: () => Promise.resolve(null),
    text: () => Promise.resolve(''),
  })
}

function jsonError(status, body, { retryAfter } = {}) {
  return Promise.resolve({
    ok: false,
    status,
    headers: {
      get: (h) => {
        const lower = h.toLowerCase()
        if (lower === 'content-type') return 'application/json'
        if (lower === 'retry-after' && retryAfter != null) return String(retryAfter)
        return null
      },
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  })
}

// ── Handler builders ──────────────────────────────────────────────────────────

/**
 * Sites list handler — GET /api/v1/portal/sites.
 * @param {{ empty?: boolean, error?: 403 | 500 }} options
 */
export function portalSitesHandler({ empty = false, error } = {}) {
  return (url, _init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    if (!urlStr.includes(`${BASE}/sites`)) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }
    if (error === 403) return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied.' })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Server error.' })
    return jsonOk(empty ? [] : sitesFixture)
  }
}

/**
 * Site assets handler — GET /api/v1/portal/sites/:siteId/assets.
 * @param {{ empty?: boolean }} options
 */
export function portalSiteAssetsHandler({ empty = false } = {}) {
  return (url, _init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    if (!urlStr.match(/\/portal\/sites\/[^/]+\/assets/)) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }
    return jsonOk(empty ? [] : assetsFixture)
  }
}

/**
 * Submission handler — POST /api/v1/portal/service-requests.
 * Tracks seen Idempotency-Keys to simulate idempotent replay.
 * @param {{ error?: 400 | 429 | 500 | 404 }} options
 */
export function portalSubmitHandler({ error } = {}) {
  const seenKeys = new Set()
  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init?.method ?? 'GET').toUpperCase()
    if (!urlStr.endsWith(`${BASE}/service-requests`) || method !== 'POST') {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }

    if (error === 400) return jsonError(400, submit400Fixture)
    if (error === 429) return jsonError(429, submit429Fixture, { retryAfter: 30 })
    if (error === 404) return jsonError(404, { code: 'PORTAL_RESOURCE_NOT_FOUND', message: 'Not found.' })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Server error.' })

    const headers = init?.headers ?? {}
    const key = typeof headers.get === 'function'
      ? headers.get('Idempotency-Key')
      : (headers['Idempotency-Key'] ?? null)

    if (key && seenKeys.has(key)) {
      return Promise.resolve({
        ok: true,
        status: 201,
        headers: {
          get: (h) => {
            const lower = h.toLowerCase()
            if (lower === 'content-type') return 'application/json'
            if (lower === 'idempotency-replay') return 'true'
            return null
          },
        },
        json: () => Promise.resolve(createdFixture),
        text: () => Promise.resolve(JSON.stringify(createdFixture)),
      })
    }
    if (key) seenKeys.add(key)

    return jsonCreated(createdFixture)
  }
}

export const STATUS_FIXTURES = {
  new: statusNewFixture,
  assigned: statusAssignedFixture,
  inProgress: statusInProgressFixture,
  completed: statusCompletedFixture,
  degraded: statusDegradedFixture,
}

/**
 * Status polling handler — GET /api/v1/portal/service-requests/:id/status.
 * @param {{
 *   state?: 'new' | 'assigned' | 'inProgress' | 'completed' | 'degraded',
 *   etag?: string,
 *   error?: 404 | 500,
 *   sequence?: Array<'200' | '304' | '200'>
 * }} options
 */
export function portalStatusHandler({ state = 'assigned', etag, error, sequence } = {}) {
  let callCount = 0
  const etagValue = etag ?? '"etag-portal-v1"'

  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    if (!urlStr.match(/\/portal\/service-requests\/[^/]+\/status/)) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }

    if (error === 404) return jsonError(404, { code: 'PORTAL_RESOURCE_NOT_FOUND', message: 'Not found.' })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Server error.' })

    const idx = callCount++

    if (sequence) {
      const step = sequence[Math.min(idx, sequence.length - 1)]
      if (step === '304') {
        return notModified()
      }
      const fixture = STATUS_FIXTURES[state] ?? STATUS_FIXTURES.assigned
      return jsonOk(fixture, { etag: etagValue })
    }

    const headers = init?.headers ?? {}
    const ifNoneMatch = typeof headers.get === 'function'
      ? headers.get('If-None-Match')
      : (headers['If-None-Match'] ?? null)

    if (ifNoneMatch && ifNoneMatch === etagValue) {
      return notModified()
    }

    const fixture = STATUS_FIXTURES[state] ?? STATUS_FIXTURES.assigned
    return jsonOk(fixture, { etag: etagValue })
  }
}

/**
 * Combined handler for all portal endpoints.
 * @param {{
 *   sitesOptions?: object,
 *   assetsOptions?: object,
 *   submitError?: number,
 *   statusState?: string,
 *   statusError?: number,
 *   statusSequence?: string[]
 * }} options
 */
export function portalHandler({
  sitesOptions = {},
  assetsOptions = {},
  submitError,
  statusState = 'assigned',
  statusError,
  statusSequence,
} = {}) {
  const sitesH = portalSitesHandler(sitesOptions)
  const assetsH = portalSiteAssetsHandler(assetsOptions)
  const submitH = portalSubmitHandler({ error: submitError })
  const statusH = portalStatusHandler({ state: statusState, error: statusError, sequence: statusSequence })

  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init?.method ?? 'GET').toUpperCase()

    if (urlStr.match(/\/portal\/service-requests\/[^/]+\/status/)) return statusH(url, init)
    if (urlStr.match(/\/portal\/sites\/[^/]+\/assets/)) return assetsH(url, init)
    if (urlStr.includes(`${BASE}/sites`)) return sitesH(url, init)
    if (urlStr.includes(`${BASE}/service-requests`) && method === 'POST') return submitH(url, init)

    return Promise.reject(new Error(`Unhandled portal URL: ${urlStr}`))
  }
}
