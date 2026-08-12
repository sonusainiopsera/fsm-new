/**
 * @fileoverview MSW-style mock fetch handlers for copilot endpoints (WO-179).
 *
 * Pattern: pure factory functions returning vi.stubGlobal-compatible mock fetch
 * functions, consistent with the existing technician.js handler pattern in this repo.
 *
 * Endpoints mocked:
 *   POST /api/v1/auth/stream-ticket  → ticket or 429
 *   GET  /api/v1/copilot/stream      → SSE stream (via ReadableStream)
 *   POST /api/v1/ai-interactions/:id/rating → 204
 */

import sseScripts from '../fixtures/copilot-sse-scripts.json'

const BASE = '/api/v1'
const TICKET_PATH   = `${BASE}/auth/stream-ticket`
const STREAM_PATH   = `${BASE}/copilot/stream`
const RATING_REGEXP = /\/api\/v1\/ai-interactions\/([^/]+)\/rating/

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonResponse(body, status = 200, headers = {}) {
  const allHeaders = { 'content-type': 'application/json', ...headers }
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (h) => allHeaders[h.toLowerCase()] ?? null,
      forEach: (fn) => Object.entries(allHeaders).forEach(([k, v]) => fn(v, k)),
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    arrayBuffer: () => Promise.resolve(new TextEncoder().encode(JSON.stringify(body)).buffer),
    clone() { return jsonResponse(body, status, headers) },
  })
}

function sseResponse(events) {
  const encoder = new TextEncoder()
  const chunks = events.map(({ event, data }) =>
    encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
  )

  let i = 0
  const stream = new ReadableStream({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(chunks[i++])
      } else {
        controller.close()
      }
    },
  })

  return Promise.resolve({
    ok: true,
    status: 200,
    headers: {
      get: (h) => {
        if (h.toLowerCase() === 'content-type') return 'text/event-stream'
        return null
      },
      forEach: (fn) => fn('text/event-stream', 'content-type'),
    },
    body: stream,
    json: () => Promise.reject(new Error('SSE response has no JSON body')),
    text: () => Promise.resolve(chunks.map(c => new TextDecoder().decode(c)).join('')),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    clone() { return sseResponse(events) },
  })
}

function errorResponse(status, body = {}, extraHeaders = {}) {
  const headers = { 'content-type': 'application/json', ...extraHeaders }
  return Promise.resolve({
    ok: false,
    status,
    headers: {
      get: (h) => headers[h.toLowerCase()] ?? null,
      forEach: (fn) => Object.entries(headers).forEach(([k, v]) => fn(v, k)),
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    clone() { return errorResponse(status, body, extraHeaders) },
  })
}

function noResponse() {
  return Promise.reject(new TypeError('Network request failed'))
}

// ── Handler factories ─────────────────────────────────────────────────────────

/**
 * Happy-path handler: returns a stream ticket and then a successful SSE stream.
 * @returns {(url: string, opts?: RequestInit) => Promise<Response>}
 */
export function copilotHappyPathHandler() {
  const { ticketResponse, events } = sseScripts.happyPath
  return function mockFetch(url) {
    const u = url.toString()
    if (u.includes(TICKET_PATH))  return jsonResponse(ticketResponse)
    if (u.includes(STREAM_PATH))  return sseResponse(events)
    if (RATING_REGEXP.test(u))    return jsonResponse(null, 204)
    return noResponse()
  }
}

/**
 * Refusal handler: returns a stream that emits no_grounded_basis.
 * @returns {(url: string, opts?: RequestInit) => Promise<Response>}
 */
export function copilotRefusalHandler() {
  const { ticketResponse, events } = sseScripts.refusal
  return function mockFetch(url) {
    const u = url.toString()
    if (u.includes(TICKET_PATH)) return jsonResponse(ticketResponse)
    if (u.includes(STREAM_PATH)) return sseResponse(events)
    return noResponse()
  }
}

/**
 * Degraded-provider handler: returns a stream that emits a partial token then degraded.
 * @returns {(url: string, opts?: RequestInit) => Promise<Response>}
 */
export function copilotDegradedHandler() {
  const { ticketResponse, events } = sseScripts.degraded
  return function mockFetch(url) {
    const u = url.toString()
    if (u.includes(TICKET_PATH)) return jsonResponse(ticketResponse)
    if (u.includes(STREAM_PATH)) return sseResponse(events)
    return noResponse()
  }
}

/**
 * Over-cap handler: ticket fetch returns 429.
 * @param {number} [retryAfterSeconds=3600]
 * @returns {(url: string, opts?: RequestInit) => Promise<Response>}
 */
export function copilotCappedHandler(retryAfterSeconds = 3600) {
  return function mockFetch(url) {
    const u = url.toString()
    if (u.includes(TICKET_PATH)) {
      return errorResponse(429, { code: 'AI_DAILY_LIMIT_REACHED' }, {
        'retry-after': String(retryAfterSeconds),
      })
    }
    return noResponse()
  }
}

/**
 * Ticket-fetch-failure handler: ticket endpoint returns 503.
 * @returns {(url: string, opts?: RequestInit) => Promise<Response>}
 */
export function copilotTicketFailureHandler() {
  return function mockFetch(url) {
    const u = url.toString()
    if (u.includes(TICKET_PATH)) return errorResponse(503, { code: 'PROVIDER_DEGRADED' })
    return noResponse()
  }
}

/**
 * Rating failure handler: rating POST returns 503.
 * @returns {(url: string, opts?: RequestInit) => Promise<Response>}
 */
export function copilotRatingFailureHandler() {
  const { ticketResponse, events } = sseScripts.happyPath
  return function mockFetch(url) {
    const u = url.toString()
    if (u.includes(TICKET_PATH))  return jsonResponse(ticketResponse)
    if (u.includes(STREAM_PATH))  return sseResponse(events)
    if (RATING_REGEXP.test(u))    return errorResponse(503, { code: 'PROVIDER_DEGRADED' })
    return noResponse()
  }
}
