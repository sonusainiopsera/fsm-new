/**
 * @fileoverview Mock transport layer — configurable latency, error injection, staleness.
 * Backed by committed JSON fixtures. Used by tests and the catalogue route.
 *
 * Extended for WO-186 data layer with:
 * - Full status code coverage (400, 401, 403, 404, 409, 422, 429, 503)
 * - ETag / 304 Not Modified support
 * - Scripted SSE event stream
 * - Paginated collection fixture
 */

import dispatcherWorkOrders from './fixtures/dispatcher-workorders.json'
import technicianJobs from './fixtures/technician-jobs.json'
import managerKpis from './fixtures/manager-kpis.json'
import customerRequests from './fixtures/customer-requests.json'

const FIXTURE_MAP = {
  'dispatcher/workorders': dispatcherWorkOrders,
  'technician/jobs': technicianJobs,
  'manager/kpis': managerKpis,
  'customer/requests': customerRequests,
}

/**
 * Platform-format error envelopes for each status code.
 * fieldErrors matches the shape consumed by FormField.
 */
export const STATUS_FIXTURES = {
  400: {
    code: 'VALIDATION_FAILED',
    message: 'Request validation failed.',
    fieldErrors: [
      { field: 'quantity', message: 'must be greater than 0' },
      { field: 'reasonCode', message: 'must not be blank' },
    ],
    traceId: 'trace-400-test',
  },
  401: {
    code: 'UNAUTHENTICATED',
    message: 'Authentication required.',
    fieldErrors: [],
    traceId: 'trace-401-test',
  },
  403: {
    code: 'FORBIDDEN',
    message: 'Access denied.',
    fieldErrors: [],
    traceId: 'trace-403-test',
  },
  404: {
    code: 'NOT_FOUND',
    message: 'The requested resource was not found.',
    fieldErrors: [],
    traceId: 'trace-404-test',
  },
  409: {
    code: 'CONFLICT',
    message: 'The operation conflicts with the current resource state.',
    fieldErrors: [],
    traceId: 'trace-409-test',
  },
  422: {
    code: 'INSUFFICIENT_STOCK',
    message: 'Insufficient stock for one or more requested lines.',
    fieldErrors: [
      { field: 'lines[0].quantity', message: 'requested 3, available 2' },
    ],
    traceId: 'trace-422-test',
  },
  429: {
    code: 'RATE_LIMITED',
    message: 'Too many requests. Please retry after 30 seconds.',
    fieldErrors: [],
    traceId: 'trace-429-test',
    retryAfterSeconds: 30,
  },
  503: {
    code: 'PROVIDER_DEGRADED',
    message: 'A required service is temporarily unavailable.',
    fieldErrors: [],
    traceId: 'trace-503-test',
  },
}

/** Scripted SSE events for testing. */
export const SSE_SCRIPT = [
  { type: 'WorkOrderAtRisk', delay: 100, payload: { workOrderId: 'wo-001', reason: 'SLA_BREACH_IMMINENT' } },
  { type: 'WorkOrderBreached', delay: 200, payload: { workOrderId: 'wo-002', reason: 'SLA_BREACHED' } },
  { type: 'WorkOrderStateChanged', delay: 300, payload: { workOrderId: 'wo-003', fromState: 'ASSIGNED', toState: 'IN_PROGRESS' } },
]

/**
 * @typedef {{
 *   latencyMs?: number,
 *   errorCode?: number | null,
 *   stale?: boolean,
 *   etag?: string | null,
 *   sendNotModified?: boolean
 * }} TransportOptions
 */

/**
 * Creates a mock transport instance with configurable behaviour.
 * @param {TransportOptions} options
 */
export function createMockTransport(options = {}) {
  const { latencyMs = 0, errorCode = null, stale = false, etag = null, sendNotModified = false } = options

  /**
   * @template T
   * @param {string} endpoint
   * @param {{ ifNoneMatch?: string }} [requestOptions]
   * @returns {Promise<{ data: T | null, error: { code: number | string, message: string } | null, stale: boolean, notModified?: boolean, etag?: string }>}
   */
  async function fetch(endpoint, requestOptions = {}) {
    if (latencyMs > 0) {
      await new Promise(r => setTimeout(r, latencyMs))
    }

    if (errorCode !== null) {
      const fixture = STATUS_FIXTURES[errorCode]
      return {
        data: null,
        error: {
          code: fixture?.code ?? errorCode,
          message: fixture?.message ?? `HTTP ${errorCode}`,
          fieldErrors: fixture?.fieldErrors ?? [],
          traceId: fixture?.traceId ?? null,
        },
        stale: false,
      }
    }

    // ETag / 304 support
    if (sendNotModified && etag && requestOptions.ifNoneMatch === etag) {
      return { data: null, error: null, stale: false, notModified: true }
    }

    const fixture = FIXTURE_MAP[endpoint]
    if (!fixture) {
      return {
        data: null,
        error: { code: 404, message: `No fixture for endpoint "${endpoint}"` },
        stale: false,
      }
    }

    return {
      data: /** @type {T} */ (fixture),
      error: null,
      stale,
      ...(etag ? { etag } : {}),
    }
  }

  return { fetch }
}

/** Default transport: no latency, no errors, fresh data */
export const defaultMockTransport = createMockTransport()

/**
 * Creates a scripted SSE event emitter for testing.
 *
 * @param {Array<{ type: string, delay: number, payload: unknown }>} script
 * @param {(event: { type: string, payload: unknown }) => void} onEvent
 * @returns {{ start: () => void, stop: () => void }}
 */
export function createSseEmitter(script = SSE_SCRIPT, onEvent) {
  let timers = []
  let started = false

  function start() {
    if (started) return
    started = true
    for (const entry of script) {
      const t = setTimeout(() => {
        onEvent({ type: entry.type, payload: entry.payload })
      }, entry.delay)
      timers.push(t)
    }
  }

  function stop() {
    timers.forEach(clearTimeout)
    timers = []
    started = false
  }

  return { start, stop }
}
