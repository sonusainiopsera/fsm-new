/**
 * @fileoverview Mock handlers for SLA-related endpoints.
 *
 * Endpoints covered:
 *   POST /api/v1/auth/stream-ticket          (SSE stream ticket)
 *   GET  /api/v1/sla-alerts/open             (open SLA alerts)
 *   GET  /api/v1/sla/breach-reason-codes     (controlled vocabulary)
 *   POST /api/v1/work-orders/:id/breach-attribution
 */

import alertsFixture from '../fixtures/sla/sla-alerts.json'
import reasonCodesFixture from '../fixtures/sla/breach-reason-codes.json'

const BASE = '/api/v1'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonOk(body) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: () => null },
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
  return Promise.resolve({
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: () => Promise.resolve(null),
  })
}

// ── Stream ticket handler ─────────────────────────────────────────────────────

let _ticketCounter = 0

/**
 * Returns a fresh unique ticket on every call (single-use enforcement).
 * @param {{ status?: 200 | 401 | 429 | 503 }} [options]
 */
export function streamTicketHandler(options = {}) {
  const { status = 200 } = options
  return (url, init) => {
    if (!url.includes('/auth/stream-ticket')) return null
    if (init?.method?.toUpperCase() !== 'POST') return null
    if (status === 401) return jsonError(401, { code: 'UNAUTHENTICATED', message: 'Token expired.' })
    if (status === 429) return jsonError(429, { code: 'RATE_LIMITED', message: 'Too many stream connections.', retryAfter: 30 })
    if (status === 503) return jsonError(503, { code: 'INTERNAL_ERROR', message: 'Stream unavailable.' })
    _ticketCounter++
    return jsonOk({ ticket: `ticket-${_ticketCounter}-${Date.now()}` })
  }
}

/** Resets ticket counter — call in beforeEach. */
export function _resetTicketCounter() { _ticketCounter = 0 }

// ── SLA alerts handler ────────────────────────────────────────────────────────

/**
 * @param {{ empty?: boolean, error?: 503 | 403 }} [options]
 */
export function slaAlertsHandler(options = {}) {
  const { empty = false, error } = options
  return (url) => {
    if (!url.includes('/sla-alerts/open')) return null
    if (error === 403) return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied.' })
    if (error === 503) return jsonError(503, { code: 'INTERNAL_ERROR', message: 'Service unavailable.' })
    if (empty) return jsonOk({ alerts: [] })
    return jsonOk(alertsFixture)
  }
}

// ── Breach reason codes handler ───────────────────────────────────────────────

export function breachReasonCodesHandler() {
  return (url) => {
    if (!url.includes('/sla/breach-reason-codes')) return null
    return jsonOk(reasonCodesFixture)
  }
}

// ── Breach attribution handler ────────────────────────────────────────────────

/**
 * @param {{ status?: 204 | 400 | 403 | 409 | 429 | 503 }} [options]
 */
export function breachAttributionHandler(options = {}) {
  const { status = 204 } = options
  return (url, init) => {
    if (!url.includes('/breach-attribution')) return null
    if (init?.method?.toUpperCase() !== 'POST') return null
    if (status === 400) return jsonError(400, {
      code: 'VALIDATION_FAILED',
      message: 'Validation failed.',
      fieldErrors: [{ field: 'reasonCode', message: 'Reason code is required.' }],
    })
    if (status === 403) return jsonError(403, { code: 'FORBIDDEN', message: 'Access denied.' })
    if (status === 409) return jsonError(409, { code: 'CONFLICT', message: 'Work order was updated. Please refresh.' })
    if (status === 429) return jsonError(429, { code: 'RATE_LIMITED', message: 'Too many requests.', retryAfter: 60 })
    if (status === 503) return jsonError(503, { code: 'INTERNAL_ERROR', message: 'Server error.' })
    return noContent()
  }
}

// ── Combined SLA handler ──────────────────────────────────────────────────────

/**
 * Composes all SLA handlers into a single fetch intercept.
 * @param {{ alertsEmpty?: boolean, ticketStatus?: number }} [options]
 */
export function slaHandler(options = {}) {
  const { alertsEmpty = false, ticketStatus = 200 } = options
  const handlers = [
    streamTicketHandler({ status: ticketStatus }),
    slaAlertsHandler({ empty: alertsEmpty }),
    breachReasonCodesHandler(),
    breachAttributionHandler(),
  ]

  return (url, init) => {
    for (const h of handlers) {
      const result = h(url, init)
      if (result !== null) return result
    }
    return null
  }
}
