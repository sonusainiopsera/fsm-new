/**
 * @fileoverview Fetch-intercept mock handlers for privacy API endpoints.
 *
 * Pattern: each handler factory returns a function compatible with
 * vi.stubGlobal('fetch', handler) for use in Vitest tests.
 *
 * Endpoints covered:
 *   GET  /api/v1/privacy/classifications
 *   PUT  /api/v1/privacy/classifications/:id
 *   GET  /api/v1/privacy/retention-policies
 *   PUT  /api/v1/privacy/retention-policies/:id
 *   POST /api/v1/privacy/retention-policies/:id/dry-run
 *   GET  /api/v1/privacy/dsar-requests
 *   GET  /api/v1/privacy/dsar-requests/:id
 *   POST /api/v1/privacy/dsar-requests/:id/transitions
 *   GET  /api/v1/privacy/dsar-requests/:id/export
 *   POST /api/v1/privacy/subjects/:type/:id/rectifications
 *   POST /api/v1/privacy/subjects/:type/:id/erasure
 *   GET  /api/v1/privacy/erasure/:id
 */

import classificationsFixture from '../fixtures/privacy/classifications.json'
import retentionPoliciesFixture from '../fixtures/privacy/retention-policies.json'
import dryRunFixture from '../fixtures/privacy/dry-run.json'
import dsarRequestsFixture from '../fixtures/privacy/dsar-requests.json'
import dsarDetailFixture from '../fixtures/privacy/dsar-detail.json'
import erasureFixture from '../fixtures/privacy/erasure.json'

const BASE = '/api/v1/privacy'

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

// ── Handler builders ──────────────────────────────────────────────────────────

/**
 * Happy-path handler: classification list.
 */
export function classificationsHandler() {
  return (url) => {
    if (url.includes(`${BASE}/classifications`)) return jsonOk(classificationsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Handler covering classifications list + PUT classification.
 * @param {{ conflict?: boolean }} options
 */
export function classificationsMutationHandler({ conflict = false } = {}) {
  return (url, opts) => {
    if (url.includes(`${BASE}/classifications`) && opts?.method === 'PUT') {
      if (conflict) {
        return jsonError(409, { status: 409, code: 'OPTIMISTIC_LOCK', message: 'Version conflict — please refresh.' })
      }
      return jsonOk({ ...classificationsFixture.data[0], tier: 'INTERNAL', version: 1 })
    }
    if (url.includes(`${BASE}/classifications`)) return jsonOk(classificationsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Happy-path handler: retention policies list.
 */
export function retentionPoliciesHandler() {
  return (url) => {
    if (url.includes(`${BASE}/retention-policies`)) return jsonOk(retentionPoliciesFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Handler for retention policies + dry-run.
 */
export function retentionWithDryRunHandler() {
  return (url, opts) => {
    if (url.includes('/dry-run') && opts?.method === 'POST') return jsonOk(dryRunFixture)
    if (url.includes(`${BASE}/retention-policies`) && opts?.method === 'PUT') {
      return jsonOk({ ...retentionPoliciesFixture.data[2], retentionPeriodDays: 2000, version: 1 })
    }
    if (url.includes(`${BASE}/retention-policies`)) return jsonOk(retentionPoliciesFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Happy-path handler: DSAR requests list.
 */
export function dsarRequestsHandler() {
  return (url) => {
    if (url.match(new RegExp(`${BASE}/dsar-requests/[^/]+$`))) return jsonOk(dsarDetailFixture)
    if (url.includes(`${BASE}/dsar-requests`)) return jsonOk(dsarRequestsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Handler: DSAR detail page (GET by id + export URL).
 * @param {{ exportError?: boolean }} options
 */
export function dsarDetailHandler({ exportError = false } = {}) {
  return (url, opts) => {
    if (url.includes('/export')) {
      if (exportError) return jsonError(403, { status: 403, code: 'FORBIDDEN', message: 'Identity not verified.' })
      return jsonOk({ downloadUrl: 'https://example.test/exports/signed-url' })
    }
    if (url.includes('/transitions') && opts?.method === 'POST') {
      return jsonOk({ ...dsarDetailFixture, state: 'VERIFIED' })
    }
    if (url.match(new RegExp(`${BASE}/dsar-requests/[^/]+$`))) return jsonOk(dsarDetailFixture)
    if (url.includes(`${BASE}/dsar-requests`)) return jsonOk(dsarRequestsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Handler: erasure initiation and status.
 * @param {{ error?: boolean }} options
 */
export function erasureHandler({ error = false } = {}) {
  return (url, opts) => {
    if (url.includes('/erasure') && opts?.method === 'POST') {
      if (error) return jsonError(422, { status: 422, code: 'ERASURE_NOT_VERIFIED', message: 'Identity not verified.' })
      return jsonOk(erasureFixture, 202)
    }
    if (url.includes('/erasure')) return jsonOk(erasureFixture)
    if (url.match(new RegExp(`${BASE}/dsar-requests/[^/]+$`))) return jsonOk(dsarDetailFixture)
    if (url.includes(`${BASE}/dsar-requests`)) return jsonOk(dsarRequestsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}

/**
 * Combined handler for the full privacy admin section (all routes).
 */
export function fullPrivacyHandler() {
  return (url, opts) => {
    if (url.includes('/dry-run') && opts?.method === 'POST') return jsonOk(dryRunFixture)
    if (url.includes('/export')) return jsonOk({ downloadUrl: 'https://example.test/exports/signed-url' })
    if (url.includes('/transitions') && opts?.method === 'POST') return jsonOk({ ...dsarDetailFixture, state: 'VERIFIED' })
    if (url.includes('/erasure') && opts?.method === 'POST') return jsonOk(erasureFixture, 202)
    if (url.includes('/erasure')) return jsonOk(erasureFixture)
    if (url.includes(`${BASE}/classifications`) && opts?.method === 'PUT') {
      return jsonOk({ ...classificationsFixture.data[0], tier: 'INTERNAL', version: 1 })
    }
    if (url.includes(`${BASE}/classifications`)) return jsonOk(classificationsFixture)
    if (url.includes(`${BASE}/retention-policies`) && opts?.method === 'PUT') {
      return jsonOk({ ...retentionPoliciesFixture.data[0], retentionPeriodDays: 2000, version: 1 })
    }
    if (url.includes(`${BASE}/retention-policies`)) return jsonOk(retentionPoliciesFixture)
    if (url.match(new RegExp(`${BASE}/dsar-requests/[^/]+$`))) return jsonOk(dsarDetailFixture)
    if (url.includes(`${BASE}/dsar-requests`)) return jsonOk(dsarRequestsFixture)
    return Promise.reject(new Error(`Unhandled URL: ${url}`))
  }
}
