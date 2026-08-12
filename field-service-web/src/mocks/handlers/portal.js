/**
 * @fileoverview Fetch-intercept mock handlers for portal endpoints.
 *
 * Endpoints covered:
 *   GET  /api/v1/portal/sites                              (customer's own sites)
 *   GET  /api/v1/portal/sites/:siteId/assets              (assets at a site)
 *   POST /api/v1/portal/service-requests                  (submission)
 *   GET  /api/v1/portal/service-requests                  (paginated history)
 *   GET  /api/v1/portal/service-requests/:id/status       (status polling with ETag)
 *   GET  /api/v1/portal/surveys                           (CSAT survey list)
 *   POST /api/v1/portal/surveys/:id/response              (submit response)
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
import historyPage1Fixture from '../fixtures/portal/portal-history-page1.json'
import historyPage2Fixture from '../fixtures/portal/portal-history-page2.json'
import historyEmptyFixture from '../fixtures/portal/portal-history-empty.json'
import surveyAnswerableFixture from '../fixtures/portal/portal-survey-answerable.json'
import survey409Fixture from '../fixtures/portal/portal-survey-409.json'
import survey422Fixture from '../fixtures/portal/portal-survey-422.json'
import surveySubmitOkFixture from '../fixtures/portal/portal-survey-submit-ok.json'

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

// ── History handler ────────────────────────────────────────────────────────────

/**
 * Service history handler — GET /api/v1/portal/service-requests (collection).
 * Multi-page: returns page1 for page=0, page2 for page=1. Both pages have
 * duplicate openedAt values across items to test exact-once rendering (AC-4).
 *
 * @param {{
 *   empty?: boolean,
 *   error?: 400 | 403 | 500,
 *   page2?: boolean
 * }} options
 */
export function portalHistoryHandler({ empty = false, error, page2 = false } = {}) {
  return (url, _init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    // Must be a GET on the base service-requests collection (not /:id/status)
    if (!urlStr.includes(`${BASE}/service-requests`) || urlStr.match(/\/service-requests\/[^?]/)) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }
    if (error === 403) return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied.' })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Server error.' })
    if (empty) return jsonOk(historyEmptyFixture)

    // Determine page from URL param
    const parsedUrl = new URL(urlStr, 'http://localhost')
    const pageParam = Number(parsedUrl.searchParams.get('page') ?? 0)
    const fixture = (pageParam >= 1 || page2) ? historyPage2Fixture : historyPage1Fixture
    return jsonOk(fixture)
  }
}

// ── Survey handlers ────────────────────────────────────────────────────────────

/**
 * Survey list handler — GET /api/v1/portal/surveys.
 * Returns the answerable, already-answered, or expired fixture.
 *
 * @param {{ state?: 'answerable' | 'empty', error?: 403 | 500 }} options
 */
export function portalSurveysHandler({ state = 'answerable', error } = {}) {
  return (url, _init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    if (!urlStr.includes(`${BASE}/surveys`) || urlStr.match(/\/surveys\/[^?]/)) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }
    if (error === 403) return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied.' })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Server error.' })
    if (state === 'empty') {
      return jsonOk({ data: [], page: { totalElements: 0, totalPages: 0, page: 0, size: 10, hasNext: false, hasPrev: false }, links: {} })
    }
    return jsonOk(surveyAnswerableFixture)
  }
}

/**
 * Survey response submission handler — POST /api/v1/portal/surveys/:id/response.
 *
 * @param {{ error?: 409 | 422 | 400 | 500 }} options
 */
export function portalSurveyResponseHandler({ error } = {}) {
  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init?.method ?? 'GET').toUpperCase()
    if (!urlStr.match(/\/portal\/surveys\/[^/]+\/response/) || method !== 'POST') {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }
    if (error === 409) return jsonError(409, survey409Fixture)
    if (error === 422) return jsonError(422, survey422Fixture)
    if (error === 400) return jsonError(400, { code: 'VALIDATION_FAILED', message: 'Invalid request.', fieldErrors: [{ field: 'score', message: 'score is required' }] })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Server error.' })
    return jsonCreated(surveySubmitOkFixture)
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
 *   statusSequence?: string[],
 *   historyOptions?: object,
 *   surveysOptions?: object,
 *   surveyResponseError?: number
 * }} options
 */
export function portalHandler({
  sitesOptions = {},
  assetsOptions = {},
  submitError,
  statusState = 'assigned',
  statusError,
  statusSequence,
  historyOptions = {},
  surveysOptions = {},
  surveyResponseError,
} = {}) {
  const sitesH = portalSitesHandler(sitesOptions)
  const assetsH = portalSiteAssetsHandler(assetsOptions)
  const submitH = portalSubmitHandler({ error: submitError })
  const statusH = portalStatusHandler({ state: statusState, error: statusError, sequence: statusSequence })
  const historyH = portalHistoryHandler(historyOptions)
  const surveysH = portalSurveysHandler(surveysOptions)
  const surveyResponseH = portalSurveyResponseHandler({ error: surveyResponseError })

  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init?.method ?? 'GET').toUpperCase()

    if (urlStr.match(/\/portal\/service-requests\/[^/]+\/status/)) return statusH(url, init)
    if (urlStr.match(/\/portal\/sites\/[^/]+\/assets/)) return assetsH(url, init)
    if (urlStr.includes(`${BASE}/sites`)) return sitesH(url, init)
    if (urlStr.match(/\/portal\/surveys\/[^/?]+\/response/) && method === 'POST') return surveyResponseH(url, init)
    if (urlStr.includes(`${BASE}/surveys`)) return surveysH(url, init)
    if (urlStr.match(/\/portal\/service-requests\?/) || urlStr.endsWith('/service-requests')) {
      if (method === 'GET') return historyH(url, init)
    }
    if (urlStr.includes(`${BASE}/service-requests`) && method === 'POST') return submitH(url, init)

    return Promise.reject(new Error(`Unhandled portal URL: ${urlStr}`))
  }
}
