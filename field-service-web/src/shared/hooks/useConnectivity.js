/**
 * @fileoverview useConnectivity — combines navigator.onLine, a conditional GET
 * heartbeat against the technician day endpoint, and query-error classification
 * to surface a reliable connectivity state.
 *
 * Design decisions:
 * - navigator.onLine alone is unreliable (can be true when captive-portalled).
 *   The heartbeat validates actual API reachability.
 * - Debounced transitions: 500ms delay before marking offline (prevents banner
 *   flicker on transient drops), 1 500ms after first success before marking
 *   online (avoids premature "reconnected" flash while retrying).
 * - NetworkError vs DegradedError: if the heartbeat gets a 5xx back, the server
 *   is reachable but degraded — we stay connected with a degraded flag.
 * - Mutations MUST call guardMutation(); callers must NOT queue when it returns
 *   false (AC-5/WO-155 constraint).
 */
import { useState, useEffect, useCallback, useRef } from 'react'

const HEARTBEAT_URL = '/api/v1/technicians/me/work-orders'
const HEARTBEAT_INTERVAL_MS = 15_000
const OFFLINE_DEBOUNCE_MS = 500
const RECONNECT_DEBOUNCE_MS = 1_500
const HEARTBEAT_TIMEOUT_MS = 5_000

/**
 * @typedef {{
 *   isConnected: boolean,
 *   isDegraded: boolean,
 *   lastConnectedAt: number | null,
 *   guardMutation: () => boolean,
 *   retryNow: () => void,
 * }} ConnectivityState
 */

/**
 * @returns {ConnectivityState}
 */
export function useConnectivity() {
  const [isConnected, setIsConnected] = useState(
    typeof navigator !== 'undefined' ? navigator.onLine : true
  )
  const [isDegraded, setIsDegraded] = useState(false)
  const [lastConnectedAt, setLastConnectedAt] = useState(null)

  const offlineTimer = useRef(null)
  const reconnectTimer = useRef(null)
  const heartbeatInterval = useRef(null)

  const scheduleOffline = useCallback(() => {
    clearTimeout(reconnectTimer.current)
    clearTimeout(offlineTimer.current)
    offlineTimer.current = setTimeout(() => {
      setIsConnected(prev => {
        if (prev) setLastConnectedAt(Date.now())
        return false
      })
    }, OFFLINE_DEBOUNCE_MS)
  }, [])

  const scheduleOnline = useCallback(() => {
    clearTimeout(offlineTimer.current)
    clearTimeout(reconnectTimer.current)
    reconnectTimer.current = setTimeout(() => {
      setIsConnected(true)
    }, RECONNECT_DEBOUNCE_MS)
  }, [])

  const runHeartbeat = useCallback(async () => {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      scheduleOffline()
      return
    }
    try {
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), HEARTBEAT_TIMEOUT_MS)
      const res = await fetch(HEARTBEAT_URL, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
        cache: 'no-store',
        credentials: 'omit',
      })
      clearTimeout(timeout)

      // 401 = server reachable (auth needed); 304/200/4xx = server reachable
      if (res.status < 500) {
        setIsDegraded(false)
        scheduleOnline()
      } else {
        // 5xx: server reachable but degraded
        setIsDegraded(true)
        scheduleOnline()
      }
    } catch {
      // Fetch aborted (timeout) or network error
      setIsDegraded(false)
      scheduleOffline()
    }
  }, [scheduleOffline, scheduleOnline])

  useEffect(() => {
    const onOnline = () => runHeartbeat()
    const onOffline = () => scheduleOffline()

    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)

    heartbeatInterval.current = setInterval(runHeartbeat, HEARTBEAT_INTERVAL_MS)

    return () => {
      window.removeEventListener('online', onOnline)
      window.removeEventListener('offline', onOffline)
      clearInterval(heartbeatInterval.current)
      clearTimeout(offlineTimer.current)
      clearTimeout(reconnectTimer.current)
    }
  }, [runHeartbeat, scheduleOffline])

  const guardMutation = useCallback(() => isConnected, [isConnected])
  const retryNow = useCallback(() => runHeartbeat(), [runHeartbeat])

  return { isConnected, isDegraded, lastConnectedAt, guardMutation, retryNow }
}
