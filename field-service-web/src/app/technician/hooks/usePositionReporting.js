/**
 * @fileoverview usePositionReporting — purpose-limited position reporting hook (WO-159).
 *
 * Reports the technician's coarse location to the server at most once per 60 seconds,
 * but only while the job state is EN_ROUTE or IN_PROGRESS.
 *
 * Privacy constraints:
 * - Position is sent only during active travel/work states.
 * - Watching stops immediately when state exits those states or the component unmounts.
 * - PERMISSION_DENIED is silently swallowed — users can deny without being blocked.
 * - Coordinates are never written to any log or debug output in this module.
 */
import { useState, useEffect, useRef, useCallback } from 'react'

const ACTIVE_STATES = new Set(['EN_ROUTE', 'IN_PROGRESS'])
const MIN_SEND_INTERVAL_MS = 60_000

const GEO_OPTIONS = {
  enableHighAccuracy: false,
  maximumAge: 30_000,
  timeout: 15_000,
}

/**
 * Reports device position to POST /api/v1/technicians/me/position.
 * Never throws — errors are returned as null.
 */
async function sendPosition(lat, lon, accuracy, capturedAt) {
  try {
    const body = {
      latitude: lat,
      longitude: lon,
      capturedAt: capturedAt,
      accuracyMetres: accuracy != null ? Math.round(accuracy) : null,
    }
    await fetch('/api/v1/technicians/me/position', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify(body),
    })
  } catch {
    // Non-fatal: a missing position must never block anything
  }
}

/**
 * @param {string | undefined} jobState - current work order state from server
 * @returns {{ isReporting: boolean, geoError: string | null }}
 */
export function usePositionReporting(jobState) {
  const [isReporting, setIsReporting] = useState(false)
  const [geoError, setGeoError]       = useState(null)

  const watchIdRef   = useRef(null)
  const lastSentRef  = useRef(0)
  const activeRef    = useRef(false)

  const handlePosition = useCallback((pos) => {
    const now = Date.now()
    if (now - lastSentRef.current < MIN_SEND_INTERVAL_MS) return

    lastSentRef.current = now
    const { latitude, longitude, accuracy } = pos.coords
    const capturedAt = new Date(pos.timestamp).toISOString()
    sendPosition(latitude, longitude, accuracy, capturedAt)
  }, [])

  const handleError = useCallback((err) => {
    // PERMISSION_DENIED (1): user has denied — stop watching, surface UI state
    // POSITION_UNAVAILABLE (2) / TIMEOUT (3): transient — clear error, keep watching
    if (err.code === 1) {
      setGeoError('location_denied')
      setIsReporting(false)
    }
  }, [])

  const startWatching = useCallback(() => {
    if (watchIdRef.current !== null) return
    if (typeof navigator === 'undefined' || !navigator.geolocation) return

    setGeoError(null)
    setIsReporting(true)
    activeRef.current = true

    watchIdRef.current = navigator.geolocation.watchPosition(
      handlePosition,
      handleError,
      GEO_OPTIONS
    )
  }, [handlePosition, handleError])

  const stopWatching = useCallback(() => {
    if (watchIdRef.current !== null) {
      navigator.geolocation.clearWatch(watchIdRef.current)
      watchIdRef.current = null
    }
    activeRef.current = false
    setIsReporting(false)
  }, [])

  useEffect(() => {
    if (ACTIVE_STATES.has(jobState)) {
      startWatching()
    } else {
      stopWatching()
    }

    return () => {
      stopWatching()
    }
  }, [jobState, startWatching, stopWatching])

  return { isReporting, geoError }
}
