/**
 * @fileoverview Fetch-intercept mock handlers for reference data API endpoints.
 *
 * Pattern: each handler factory returns a function compatible with
 * vi.stubGlobal('fetch', handler) for use in Vitest tests.
 *
 * Endpoints covered:
 *   GET  /api/v1/customers
 *   POST /api/v1/customers
 *   PUT  /api/v1/customers/:id
 *   GET  /api/v1/sites
 *   POST /api/v1/sites
 *   PUT  /api/v1/sites/:id
 *   GET  /api/v1/assets
 *   POST /api/v1/assets
 *   PUT  /api/v1/assets/:id
 *   GET  /api/v1/technicians
 *   POST /api/v1/technicians
 *   PUT  /api/v1/technicians/:id
 *   GET  /api/v1/technicians/:id/certifications
 *   PUT  /api/v1/technicians/:id/certifications
 *   GET  /api/v1/skills
 *   POST /api/v1/skills
 *   PUT  /api/v1/skills/:id
 *   GET  /api/v1/certification-types
 *   POST /api/v1/certification-types
 *   PUT  /api/v1/certification-types/:id
 */

import customersFixture from '../fixtures/refdata/customers.json'
import certTypesFixture from '../fixtures/refdata/certification-types.json'
import techniciansFixture from '../fixtures/refdata/technicians.json'
import techCertsFixture from '../fixtures/refdata/technician-certifications.json'
import sitesFixture from '../fixtures/refdata/sites.json'
import assetsFixture from '../fixtures/refdata/assets.json'
import skillsFixture from '../fixtures/refdata/skills.json'
import error400Fixture from '../fixtures/refdata/error-400.json'
import error403Fixture from '../fixtures/refdata/error-403.json'
import error409Fixture from '../fixtures/refdata/error-409.json'
import error422Fixture from '../fixtures/refdata/error-422.json'

const BASE = '/api/v1'

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
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  })
}

function noContent() {
  return Promise.resolve({ ok: true, status: 204, headers: { get: () => null }, json: () => Promise.resolve(null) })
}

/**
 * Returns a combined handler routing all reference data endpoints.
 *
 * @param {{ errorCode?: number, technicianId?: string }} options
 */
export function refdataHandlers({ errorCode, technicianId } = {}) {
  return async (url, opts = {}) => {
    if (errorCode) {
      const errorMap = { 400: error400Fixture, 403: error403Fixture, 409: error409Fixture, 422: error422Fixture }
      return jsonError(errorCode, errorMap[errorCode] ?? { code: 'ERROR', message: `HTTP ${errorCode}` })
    }

    const method = (opts.method ?? 'GET').toUpperCase()

    // Customers
    if (url.includes(`${BASE}/customers`) && method === 'GET') return jsonOk(customersFixture)
    if (url.includes(`${BASE}/customers`) && method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: 'cust-new-0000-0000-000000000099', ...body, active: true }, 201)
    }
    if (url.match(/\/api\/v1\/customers\/[^/]+$/) && method === 'PUT') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: url.split('/').pop(), ...body, active: true })
    }

    // Sites
    if (url.includes(`${BASE}/sites`) && method === 'GET') return jsonOk(sitesFixture)
    if (url.includes(`${BASE}/sites`) && method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: 'site-new-0000-0000-000000000099', ...body, active: true }, 201)
    }
    if (url.match(/\/api\/v1\/sites\/[^/]+$/) && method === 'PUT') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: url.split('/').pop(), ...body, active: true })
    }

    // Assets
    if (url.includes(`${BASE}/assets`) && method === 'GET') return jsonOk(assetsFixture)
    if (url.includes(`${BASE}/assets`) && method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: 'asset-new-000-0000-000000000099', ...body, active: true }, 201)
    }
    if (url.match(/\/api\/v1\/assets\/[^/]+$/) && method === 'PUT') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: url.split('/').pop(), ...body, active: true })
    }

    // Skills
    if (url.includes(`${BASE}/skills`) && method === 'GET') return jsonOk(skillsFixture)
    if (url.includes(`${BASE}/skills`) && method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: 'skill-new-0000-0000-000000000099', ...body, active: true }, 201)
    }
    if (url.match(/\/api\/v1\/skills\/[^/]+$/) && method === 'PUT') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: url.split('/').pop(), ...body, active: true })
    }

    // Certification types
    if (url.includes(`${BASE}/certification-types`) && method === 'GET') return jsonOk(certTypesFixture)
    if (url.includes(`${BASE}/certification-types`) && method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: 'cert-type-new-0000-000000000099', ...body, active: true, version: 1 }, 201)
    }
    if (url.match(/\/api\/v1\/certification-types\/[^/]+$/) && method === 'PUT') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: url.split('/').pop(), ...body, version: 2 })
    }

    // Technicians
    if (url.match(/\/api\/v1\/technicians\/[^/]+\/certifications/) && method === 'GET') {
      return jsonOk(techCertsFixture)
    }
    if (url.match(/\/api\/v1\/technicians\/[^/]+\/certifications/) && method === 'PUT') {
      return jsonOk(techCertsFixture.data)
    }
    if (url.includes(`${BASE}/technicians`) && method === 'GET') return jsonOk(techniciansFixture)
    if (url.includes(`${BASE}/technicians`) && method === 'POST') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: 'tech-new-0000-0000-000000000099', ...body, active: true }, 201)
    }
    if (url.match(/\/api\/v1\/technicians\/[^/]+$/) && method === 'PUT') {
      const body = opts.body ? JSON.parse(opts.body) : {}
      return jsonOk({ id: url.split('/').pop(), ...body, active: true })
    }

    return Promise.reject(new Error(`[refdataHandlers] Unhandled URL: ${url}`))
  }
}

/**
 * Handler that returns 403 for all write operations.
 */
export function refdataReadOnlyHandlers() {
  return async (url, opts = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    if (method !== 'GET') return jsonError(403, error403Fixture)
    return refdataHandlers()(url, opts)
  }
}

/**
 * Handler simulating a 409 Conflict on POST.
 */
export function refdataConflictOnCreateHandler() {
  return async (url, opts = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    if (method === 'POST') return jsonError(409, error409Fixture)
    return refdataHandlers()(url, opts)
  }
}

/**
 * Handler simulating 400 field validation errors on POST/PUT.
 */
export function refdataValidationErrorHandler() {
  return async (url, opts = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    if (method === 'POST' || method === 'PUT') return jsonError(400, error400Fixture)
    return refdataHandlers()(url, opts)
  }
}
