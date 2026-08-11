/**
 * @fileoverview SSE client with single-use stream tickets and exponential backoff.
 *
 * The browser EventSource API cannot set an Authorization header, so this
 * client follows the platform ticketing protocol:
 * 1. Exchange the bearer token for a single-use stream ticket (60 s TTL).
 * 2. Open EventSource with the ticket as a query parameter (never the token).
 * 3. On connection drop: wait with jittered exponential backoff, request a
 *    fresh ticket, and reconnect. A consumed ticket is never reused.
 * 4. On event arrival: parse the JSON payload and dispatch invalidations via
 *    eventKeyMap to the TanStack Query client.
 *
 * RULE: The access token must never appear in a URL (WO-186 constraint).
 */

import { getToken } from './tokenStore.js'
import { getInvalidationKeys } from './eventKeyMap.js'

const TICKET_ENDPOINT = '/api/v1/auth/stream-ticket'
const SSE_BASE = '/api/v1/stream'

const BASE_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 60_000
const MAX_RECONNECT_ATTEMPTS = 10

/**
 * @typedef {{
 *   invalidate: (queryKey: string[]) => void
 * }} QueryInvalidator
 */

/**
 * Starts the SSE client. Returns a dispose function that closes the connection.
 *
 * @param {QueryInvalidator} invalidator  Typically queryClient.invalidateQueries
 * @param {{ sseUrl?: string, ticketEndpoint?: string }} [options]
 * @returns {{ dispose: () => void }}
 */
export function startSseClient(invalidator, options = {}) {
  const sseUrl = options.sseUrl ?? SSE_BASE
  const ticketEndpoint = options.ticketEndpoint ?? TICKET_ENDPOINT

  let disposed = false
  let eventSource = /** @type {EventSource | null} */ (null)
  let reconnectAttempt = 0
  let reconnectTimer = /** @type {ReturnType<typeof setTimeout> | null} */ (null)

  async function connect() {
    if (disposed) return

    const token = getToken()
    if (!token) {
      // Not authenticated — wait and retry
      scheduleReconnect()
      return
    }

    let ticket
    try {
      ticket = await fetchStreamTicket(token, ticketEndpoint)
    } catch (err) {
      // Ticket fetch failed — backoff and retry
      scheduleReconnect()
      return
    }

    if (disposed) return

    const url = `${sseUrl}?ticket=${encodeURIComponent(ticket)}`
    eventSource = new EventSource(url)

    eventSource.onopen = () => {
      reconnectAttempt = 0  // Reset backoff on successful connection
    }

    eventSource.onmessage = (event) => {
      handleEvent(event.data, invalidator)
    }

    eventSource.addEventListener('WorkOrderAtRisk', (event) => {
      handleEvent(event.data, invalidator, 'WorkOrderAtRisk')
    })

    eventSource.addEventListener('WorkOrderBreached', (event) => {
      handleEvent(event.data, invalidator, 'WorkOrderBreached')
    })

    eventSource.addEventListener('WorkOrderStateChanged', (event) => {
      handleEvent(event.data, invalidator, 'WorkOrderStateChanged')
    })

    eventSource.onerror = () => {
      // EventSource errors: close current source and reconnect with backoff
      if (eventSource) {
        eventSource.close()
        eventSource = null
      }
      scheduleReconnect()
    }
  }

  function scheduleReconnect() {
    if (disposed) return
    if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) return

    const delay = jitteredBackoff(reconnectAttempt)
    reconnectAttempt += 1
    reconnectTimer = setTimeout(connect, delay)
  }

  function dispose() {
    disposed = true
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  // Kick off first connection
  connect()

  return { dispose }
}

/**
 * Fetches a single-use stream ticket from the auth endpoint.
 *
 * @param {string} token
 * @param {string} endpoint
 * @returns {Promise<string>}
 */
async function fetchStreamTicket(token, endpoint) {
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  })
  if (!res.ok) {
    throw new Error(`Stream ticket request failed: ${res.status}`)
  }
  const body = await res.json()
  if (!body?.ticket) {
    throw new Error('Stream ticket response missing ticket field')
  }
  return body.ticket
}

/**
 * Parses and dispatches an SSE event payload.
 *
 * @param {string} data
 * @param {QueryInvalidator} invalidator
 * @param {string} [knownType]
 */
function handleEvent(data, invalidator, knownType) {
  let parsed
  try {
    parsed = JSON.parse(data)
  } catch {
    return  // Malformed event — ignore
  }

  const eventType = knownType ?? parsed?.eventType ?? parsed?.type
  if (!eventType) return

  const keys = getInvalidationKeys(eventType)
  for (const key of keys) {
    try {
      invalidator.invalidate(key)
    } catch {
      // Never fail on invalidation errors
    }
  }
}

/**
 * Jittered exponential backoff.
 *
 * @param {number} attempt  0-based
 * @returns {number}  Delay in milliseconds
 */
export function jitteredBackoff(attempt) {
  const exp = BASE_BACKOFF_MS * Math.pow(2, attempt)
  const jitter = Math.random() * BASE_BACKOFF_MS
  return Math.min(exp + jitter, MAX_BACKOFF_MS)
}
