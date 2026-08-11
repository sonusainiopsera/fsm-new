/**
 * @fileoverview Fetch-intercept mock handlers for work order board endpoints.
 *
 * Pattern: handler factories compatible with vi.stubGlobal('fetch', handler).
 *
 * Endpoints covered:
 *   GET  /api/v1/work-orders          (board search with ETag)
 *   POST /api/v1/work-orders/:id/transitions
 */

import boardFixture from '../fixtures/workorders/work-orders-board.json'
import emptyFixture from '../fixtures/workorders/work-orders-empty.json'
import refusalsFixture from '../fixtures/workorders/transition-refusals.json'

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
 * @param {{ boardOptions?: object, transitionRefusal?: string }} options
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function workOrdersHandler({ boardOptions = {}, transitionRefusal } = {}) {
  const boardH = workOrderBoardHandler(boardOptions)
  const transH = workOrderTransitionHandler({ refusal: transitionRefusal })

  return (url, init = {}) => {
    const urlStr = typeof url === 'string' ? url : url.url
    if (urlStr.includes('/transitions')) return transH(url, init)
    if (urlStr.includes(`${BASE}/work-orders`)) return boardH(url, init)
    return Promise.reject(new Error(`Unhandled URL: ${urlStr}`))
  }
}

/**
 * Resets the stored ETag (test utility).
 */
export function _resetBoardEtag() {
  _boardEtag = '"etag-board-v1"'
}
