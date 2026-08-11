/**
 * @fileoverview Mock transport layer — configurable latency, error injection, staleness.
 * Backed by committed JSON fixtures. Used by tests and the catalogue route.
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
 * @typedef {{
 *   latencyMs?: number,
 *   errorCode?: number | null,
 *   stale?: boolean
 * }} TransportOptions
 */

/**
 * Creates a mock transport instance with configurable behaviour.
 * @param {TransportOptions} options
 */
export function createMockTransport(options = {}) {
  const { latencyMs = 0, errorCode = null, stale = false } = options

  /**
   * @template T
   * @param {string} endpoint
   * @returns {Promise<{ data: T | null, error: { code: number, message: string } | null, stale: boolean }>}
   */
  async function fetch(endpoint) {
    if (latencyMs > 0) {
      await new Promise(r => setTimeout(r, latencyMs))
    }

    if (errorCode !== null) {
      const messages = {
        403: 'Access denied.',
        404: 'Resource not found.',
        409: 'Conflict — the resource state has changed.',
        422: 'Validation failed.',
        500: 'Internal server error.',
      }
      return {
        data: null,
        error: { code: errorCode, message: messages[errorCode] ?? `HTTP ${errorCode}` },
        stale: false,
      }
    }

    const fixture = FIXTURE_MAP[endpoint]
    if (!fixture) {
      return {
        data: null,
        error: { code: 404, message: `No fixture for endpoint "${endpoint}"` },
        stale: false,
      }
    }

    return { data: /** @type {T} */ (fixture), error: null, stale }
  }

  return { fetch }
}

/** Default transport: no latency, no errors, fresh data */
export const defaultMockTransport = createMockTransport()
