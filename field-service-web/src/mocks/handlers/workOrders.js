/**
 * @fileoverview Fetch-intercept mock handlers for work order board endpoints.
 *
 * Pattern: handler factories compatible with vi.stubGlobal('fetch', handler).
 *
 * Endpoints covered:
 *   GET  /api/v1/work-orders          (board search with ETag)
 *   POST /api/v1/work-orders          (work order creation)
 *   POST /api/v1/work-orders/:id/transitions
 *   GET  /api/v1/sla-policies/:priority
 */

import boardFixture from '../fixtures/workorders/work-orders-board.json'
import emptyFixture from '../fixtures/workorders/work-orders-empty.json'
import refusalsFixture from '../fixtures/workorders/transition-refusals.json'
import slaPoliciesFixture from '../fixtures/workorders/sla-policies.json'
import createdFixture from '../fixtures/workorders/work-order-created.json'
import create400Fixture from '../fixtures/workorders/create-wo-400.json'
import create422Fixture from '../fixtures/workorders/create-wo-422.json'
import create429Fixture from '../fixtures/workorders/create-wo-429.json'

const BASE = '/api/v1'
let _boardEtag = '"etag-board-v1"'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonOk(body, { etag } = {}) {
  const headers = {
    get: (h) => {
      const lower = h.toLowerCase()
      if (lower === 'content-type') return 'application/json'
      if (lower === 'etag' && etag) return etag
      return null
    },
  }
  return Promise.resolve({
    ok: true,
    status: 200,
    headers,
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
  return Promise.resolve({
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: () => Promise.resolve(null),
  })
}

// ── Handler builders ──────────────────────────────────────────────────────────

/**
 * Board search handler — returns fixture data with ETag support.
 * @param {{ empty?: boolean, etag?: string, error?: 403 | 503 | 500 }} options
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function workOrderBoardHandler({ empty = false, error } = {}) {
  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url

    // Only handle work-orders search
    if (!urlStr.includes(`${BASE}/work-orders`)) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }

    if (error === 403) return jsonError(403, refusalsFixture.forbidden.body)
    if (error === 503) return jsonError(503, { code: 'SERVICE_UNAVAILABLE', message: 'Temporarily unavailable.', traceId: 'trace-503' })
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Internal server error.', traceId: 'trace-500' })

    // ETag conditional GET support
    const headers = init?.headers ?? {}
    const ifNoneMatch = typeof headers.get === 'function' ? headers.get('If-None-Match') : headers['If-None-Match']
    if (ifNoneMatch && ifNoneMatch === _boardEtag) {
      return notModified()
    }

    const fixture = empty ? emptyFixture : boardFixture
    return jsonOk(fixture, { etag: _boardEtag })
  }
}

/**
 * Transition handler — posts a lifecycle event.
 * @param {{ refusal?: 'illegal_transition' | 'version_conflict' | 'guard_refused' | 'forbidden' }} options
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function workOrderTransitionHandler({ refusal } = {}) {
  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url

    if (!urlStr.includes('/transitions')) {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }

    if (refusal) {
      const { status, body } = refusalsFixture[refusal]
      return jsonError(status, body)
    }

    // Success: return updated work order row
    return noContent()
  }
}

/**
 * Combined handler for all work order endpoints.
 *
 * @param {{
 *   boardOptions?: object,
 *   transitionRefusal?: string,
 *   createError?: 400 | 422 | 429 | 500
 * }} options
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function workOrdersHandler({ boardOptions = {}, transitionRefusal, createError } = {}) {
  const boardH = workOrderBoardHandler(boardOptions)
  const transH = workOrderTransitionHandler({ refusal: transitionRefusal })
  const createH = workOrderCreateHandler({ error: createError })
  const slaH = slaPolicyHandler()

  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init?.method ?? 'GET').toUpperCase()

    if (urlStr.includes('/transitions')) return transH(url, init)
    if (urlStr.includes('/sla-policies/')) return slaH(url, init)
    if (urlStr.includes(`${BASE}/work-orders`) && method === 'POST') return createH(url, init)
    if (urlStr.includes(`${BASE}/work-orders`)) return boardH(url, init)
    return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
  }
}

/**
 * Work order creation handler — handles POST /api/v1/work-orders.
 *
 * @param {{ error?: 400 | 422 | 429 | 500, idempotentKey?: string }} options
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function workOrderCreateHandler({ error, idempotentKey } = {}) {
  const seenKeys = new Set()

  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const method = (init.method ?? 'GET').toUpperCase()

    if (!urlStr.endsWith(`${BASE}/work-orders`) || method !== 'POST') {
      return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
    }

    if (error === 400) return jsonError(400, create400Fixture)
    if (error === 422) return jsonError(422, create422Fixture)
    if (error === 429) return jsonError(429, create429Fixture)
    if (error === 500) return jsonError(500, { code: 'INTERNAL_ERROR', message: 'Internal server error.', traceId: 'trace-500' })

    // Idempotency: second submission with same key returns the same 201
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

    return Promise.resolve({
      ok: true,
      status: 201,
      headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
      json: () => Promise.resolve(createdFixture),
      text: () => Promise.resolve(JSON.stringify(createdFixture)),
    })
  }
}

/**
 * SLA policy lookup handler — handles GET /api/v1/sla-policies/:priority.
 *
 * @param {{ missingPriority?: string }} options
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function slaPolicyHandler({ missingPriority } = {}) {
  return (url, _init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    const match = urlStr.match(/\/api\/v1\/sla-policies\/([^?]+)/)
    if (!match) return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))

    const priority = match[1].toUpperCase()
    if (priority === (missingPriority ?? '').toUpperCase()) {
      return jsonError(404, { code: 'NOT_FOUND', message: `No SLA policy for priority ${priority}`, traceId: 'trace-sla-404' })
    }

    const policy = slaPoliciesFixture[priority]
    if (!policy) {
      return jsonError(404, { code: 'NOT_FOUND', message: `No SLA policy for priority ${priority}`, traceId: 'trace-sla-404' })
    }

    return Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
      json: () => Promise.resolve(policy),
      text: () => Promise.resolve(JSON.stringify(policy)),
    })
  }
}

/**
 * Resets the stored ETag (test utility).
 */
export function _resetBoardEtag() {
  _boardEtag = '"etag-board-v1"'
}
