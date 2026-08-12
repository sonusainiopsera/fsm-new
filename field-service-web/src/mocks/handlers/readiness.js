/**
 * @fileoverview Fetch-intercept mock handlers for readiness report endpoints.
 *
 * Endpoints covered:
 *   GET  /api/v1/reports/certification-readiness
 *   GET  /api/v1/reports/certification-readiness/gaps
 *   GET  /api/v1/reports/certification-readiness/gaps.csv
 *   GET  /api/v1/reports/certification-readiness/snapshots
 *   POST /api/v1/reports/certification-readiness/snapshots
 *   GET  /api/v1/reports/certification-readiness/requirements
 */

import summaryFixture   from '../fixtures/readiness-summary.json'
import gapsFixture      from '../fixtures/readiness-gaps.json'
import snapshotsFixture from '../fixtures/readiness-snapshots.json'

const BASE = '/api/v1/reports/certification-readiness'

function jsonOk(body, status = 200) {
  return Promise.resolve({
    ok: true,
    status,
    headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
    json:    () => Promise.resolve(body),
    text:    () => Promise.resolve(JSON.stringify(body)),
  })
}

function jsonError(status, body) {
  return Promise.resolve({
    ok: false,
    status,
    headers: { get: () => null },
    json:    () => Promise.resolve(body),
    text:    () => Promise.resolve(JSON.stringify(body)),
  })
}

function csvOk(body) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: {
      get: (h) => {
        const lower = h.toLowerCase()
        if (lower === 'content-type')        return 'text/csv; charset=UTF-8'
        if (lower === 'content-disposition') return 'attachment; filename="certification-readiness-gaps.csv"'
        return null
      },
    },
    text: () => Promise.resolve(body),
    json: () => Promise.reject(new Error('Not JSON')),
  })
}

/**
 * Creates a fetch mock handler for readiness endpoints.
 *
 * @param {{ role?: string, summary?: object, gaps?: object, snapshots?: object }} options
 * @returns {(url: string, opts: RequestInit) => Promise<Response>}
 */
export function createReadinessHandlers({
  role      = 'ADMIN',
  summary   = summaryFixture,
  gaps      = gapsFixture,
  snapshots = snapshotsFixture,
} = {}) {
  const forbidden = ['DISPATCHER', 'TECHNICIAN', 'CUSTOMER']
  const isForbidden = forbidden.includes(role)

  return (url, _opts) => {
    const path = url.replace(/\?.*$/, '')

    if (path === `${BASE}`) {
      return isForbidden ? jsonError(403, { code: 'FORBIDDEN', message: 'Access denied' }) : jsonOk(summary)
    }

    if (path === `${BASE}/gaps.csv`) {
      if (isForbidden) return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied' })
      const csv = 'technician_id,employee_code,display_name,missing_fields,missing_cert_types,expired_cert_types,expiring_soon_cert_types\n'
      return csvOk(csv)
    }

    if (path === `${BASE}/gaps`) {
      return isForbidden ? jsonError(403, { code: 'FORBIDDEN', message: 'Access denied' }) : jsonOk(gaps)
    }

    if (path === `${BASE}/snapshots`) {
      return isForbidden ? jsonError(403, { code: 'FORBIDDEN', message: 'Access denied' }) : jsonOk(snapshots)
    }

    if (path === `${BASE}/requirements`) {
      if (role !== 'ADMIN') return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied' })
      return jsonOk([
        { id: 'req-1', requirementKind: 'PROFILE_FIELD', fieldName: 'employee_no', certificationTypeCode: null, active: true, version: 0 },
        { id: 'req-2', requirementKind: 'CERTIFICATION_TYPE', fieldName: null, certificationTypeCode: 'GAS_SAFE', active: true, version: 0 },
      ])
    }

    return undefined
  }
}
