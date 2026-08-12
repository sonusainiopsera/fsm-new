/**
 * @fileoverview useSlaAlertStream — React hook owning the full SSE connection lifecycle.
 *
 * Responsibilities:
 * - Requests a fresh single-use stream ticket for every connection attempt.
 * - Opens EventSource with the ticket as a query parameter (never the access token).
 * - On any error: closes EventSource immediately (prevents native auto-retry).
 * - Reconnects with capped jittered exponential backoff.
 * - Tracks Last-Event-ID in a ref and sends it on each reconnect.
 * - Detects heartbeat staleness: after STALE_THRESHOLD_MULTIPLIER × heartbeat interval
 *   with no frame, transitions to stale state.
 * - On 401 ticket request: triggers a single-flight silent refresh, retries once;
 *   on second failure routes to signed-out state.
 * - Invalidates TanStack Query cache on SLA_AT_RISK / SLA_BREACHED / heartbeat frames.
 *
 * SECURITY: access token never placed in a URL (WO-147 constraint).
 *
 * @param {{ queryClient: import('@tanstack/react-query').QueryClient }} deps
 * @param {{ sseBase?: string, ticketEndpoint?: string, heartbeatIntervalMs?: number }} [options]
 * @returns {{
 *   status: 'live' | 'reconnecting' | 'stale',
 *   lastEventAt: number | null,
 *   lastEventId: string | null,
 * }}
 */

import { useEffect, useRef, useState, useCallback } from 'react'
import { getToken, refreshToken, isRefreshing, queueBehindRefresh, signOut } from '../../api/tokenStore.js'

const TICKET_ENDPOINT = '/api/v1/auth/stream-ticket'
const SSE_BASE = '/api/v1/stream/sla'
const BASE_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 30_000
/** Marks stale after this many multiples of heartbeatIntervalMs with no frame. */
const STALE_THRESHOLD_MULTIPLIER = 3
const DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000

/**
 * @param {number} attempt  0-based
 * @returns {number}
 */
export function slaJitteredBackoff(attempt) {
  const exp = BASE_BACKOFF_MS * Math.pow(2, attempt)
  const jitter = Math.random() * BASE_BACKOFF_MS
  return Math.min(exp + jitter, MAX_BACKOFF_MS)
}

/**
 * @param {{
 *   queryClient: import('@tanstack/react-query').QueryClient
 * }} deps
 * @param {{
 *   sseBase?: string,
 *   ticketEndpoint?: string,
 *   heartbeatIntervalMs?: number,
 * }} [options]
 */
export function useSlaAlertStream(deps, options = {}) {
  const { queryClient } = deps
  const sseBase = options.sseBase ?? SSE_BASE
  const ticketEndpoint = options.ticketEndpoint ?? TICKET_ENDPOINT
  const heartbeatIntervalMs = options.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_INTERVAL_MS

  const [status, setStatus] = useState(/** @type {'live'|'reconnecting'|'stale'} */ ('reconnecting'))
  const [lastEventAt, setLastEventAt] = useState(/** @type {number|null} */ (null))
  const [lastEventId, setLastEventId] = useState(/** @type {string|null} */ (null))

  const lastEventIdRef = useRef(/** @type {string|null} */ (null))
  const reconnectAttemptRef = useRef(0)
  const disposedRef = useRef(false)
  const eventSourceRef = useRef(/** @type {EventSource|null} */ (null))
  const reconnectTimerRef = useRef(/** @type {ReturnType<typeof setTimeout>|null} */ (null))
  const stalenessTimerRef = useRef(/** @type {ReturnType<typeof setTimeout>|null} */ (null))

  // Stable invalidator reference
  const invalidateRef = useRef(queryClient)
  useEffect(() => { invalidateRef.current = queryClient }, [queryClient])

  const resetStalenessTimer = useCallback(() => {
    if (stalenessTimerRef.current !== null) {
      clearTimeout(stalenessTimerRef.current)
    }
    const threshold = heartbeatIntervalMs * STALE_THRESHOLD_MULTIPLIER
    stalenessTimerRef.current = setTimeout(() => {
      if (!disposedRef.current) {
        setStatus('stale')
      }
    }, threshold)
  }, [heartbeatIntervalMs])

  const markLive = useCallback((eventId) => {
    setStatus('live')
    const now = Date.now()
    setLastEventAt(now)
    if (eventId) {
      lastEventIdRef.current = eventId
      setLastEventId(eventId)
    }
    resetStalenessTimer()
  }, [resetStalenessTimer])

  const invalidate = useCallback((keys) => {
    const qc = invalidateRef.current
    if (!qc) return
    for (const key of keys) {
      try {
        qc.invalidateQueries({ queryKey: key })
      } catch {
        // Never fail on invalidation errors
      }
    }
  }, [])

  useEffect(() => {
    disposedRef.current = false

    async function fetchTicket(retried = false) {
      if (disposedRef.current) return null
      const token = getToken()
      if (!token) return null

      const res = await fetch(ticketEndpoint, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
      })

      if (res.status === 401 && !retried) {
        // Single-flight silent refresh, then retry once
        try {
          if (isRefreshing()) {
            await queueBehindRefresh()
          } else {
            await refreshToken()
          }
          return fetchTicket(/* retried */ true)
        } catch {
          signOut()
          return null
        }
      }

      if (!res.ok) {
        throw new Error(`Ticket request failed: ${res.status}`)
      }

      const body = await res.json()
      if (!body?.ticket) throw new Error('Missing ticket in response')
      return body.ticket
    }

    async function connect() {
      if (disposedRef.current) return

      setStatus('reconnecting')

      let ticket
      try {
        ticket = await fetchTicket()
      } catch {
        scheduleReconnect()
        return
      }

      if (!ticket || disposedRef.current) return

      // Build URL with ticket and Last-Event-ID
      let url = `${sseBase}?ticket=${encodeURIComponent(ticket)}`
      if (lastEventIdRef.current) {
        url += `&lastEventId=${encodeURIComponent(lastEventIdRef.current)}`
      }

      const es = new EventSource(url)
      eventSourceRef.current = es

      es.onopen = () => {
        reconnectAttemptRef.current = 0
        resetStalenessTimer()
      }

      function handleSlaFrame(event) {
        markLive(event.lastEventId || null)
        let payload
        try { payload = JSON.parse(event.data) } catch { return }

        const eventType = event.type
        if (eventType === 'SLA_AT_RISK' || eventType === 'WorkOrderAtRisk') {
          invalidate([['workOrders'], ['slaAlerts']])
        } else if (eventType === 'SLA_BREACHED' || eventType === 'WorkOrderBreached') {
          invalidate([['workOrders'], ['slaAlerts']])
        } else if (eventType === 'heartbeat') {
          // Heartbeat counts as activity; no invalidation needed
        } else {
          invalidate([['workOrders'], ['slaAlerts']])
        }
        void payload // used for side effects only
      }

      function handleHeartbeat(event) {
        markLive(event.lastEventId || null)
      }

      es.onmessage = handleSlaFrame
      es.addEventListener('SLA_AT_RISK', handleSlaFrame)
      es.addEventListener('SLA_BREACHED', handleSlaFrame)
      es.addEventListener('WorkOrderAtRisk', handleSlaFrame)
      es.addEventListener('WorkOrderBreached', handleSlaFrame)
      es.addEventListener('heartbeat', handleHeartbeat)

      es.onerror = () => {
        // Close immediately — never let browser auto-retry a consumed ticket
        es.close()
        if (eventSourceRef.current === es) {
          eventSourceRef.current = null
        }
        scheduleReconnect()
      }
    }

    function scheduleReconnect() {
      if (disposedRef.current) return
      const delay = slaJitteredBackoff(reconnectAttemptRef.current)
      reconnectAttemptRef.current += 1
      reconnectTimerRef.current = setTimeout(connect, delay)
    }

    // Initial connection
    connect()

    return () => {
      disposedRef.current = true
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
      if (stalenessTimerRef.current !== null) {
        clearTimeout(stalenessTimerRef.current)
        stalenessTimerRef.current = null
      }
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
        eventSourceRef.current = null
      }
    }
  }, [sseBase, ticketEndpoint, heartbeatIntervalMs, invalidate, markLive, resetStalenessTimer])

  return { status, lastEventAt, lastEventId }
}
