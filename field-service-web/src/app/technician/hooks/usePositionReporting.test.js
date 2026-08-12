/**
 * @fileoverview Unit tests for usePositionReporting (WO-159).
 *
 * Tests cover:
 *   - Reporting starts on EN_ROUTE / IN_PROGRESS state
 *   - Reporting stops on non-active state (ASSIGNED, ON_HOLD, COMPLETED)
 *   - 60-second throttle prevents duplicate sends within window
 *   - PERMISSION_DENIED (code 1) sets geoError and stops reporting
 *   - POSITION_UNAVAILABLE (code 2) / TIMEOUT (code 3) are non-fatal
 *   - Reporting stops on unmount
 *   - Missing geolocation API is a no-op
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { usePositionReporting } from './usePositionReporting.js'

// ── Geo mock factory ──────────────────────────────────────────────────────────

function makeGeoMock() {
  const watchers = new Map()
  let nextId = 1

  return {
    watchPosition: vi.fn((success, error, opts) => {
      const id = nextId++
      watchers.set(id, { success, error })
      return id
    }),
    clearWatch: vi.fn((id) => { watchers.delete(id) }),
    _triggerSuccess: (pos) => {
      watchers.forEach(({ success }) => success(pos))
    },
    _triggerError: (err) => {
      watchers.forEach(({ error }) => error(err))
    },
    _watcherCount: () => watchers.size,
  }
}

function makePosition(lat = 51.5, lon = -0.1, accuracy = 10, tsOffset = 0) {
  return {
    coords: { latitude: lat, longitude: lon, accuracy },
    timestamp: Date.now() + tsOffset,
  }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('usePositionReporting', () => {
  let geo
  let fetchCalls

  beforeEach(() => {
    geo = makeGeoMock()
    vi.stubGlobal('navigator', { geolocation: geo })

    fetchCalls = []
    vi.stubGlobal('fetch', vi.fn(async (url, opts) => {
      fetchCalls.push({ url, opts })
      return { ok: true, status: 202 }
    }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('starts watching when state is EN_ROUTE', () => {
    const { result } = renderHook(() => usePositionReporting('EN_ROUTE'))
    expect(result.current.isReporting).toBe(true)
    expect(geo.watchPosition).toHaveBeenCalledOnce()
  })

  it('starts watching when state is IN_PROGRESS', () => {
    const { result } = renderHook(() => usePositionReporting('IN_PROGRESS'))
    expect(result.current.isReporting).toBe(true)
  })

  it('does not start watching when state is ASSIGNED', () => {
    const { result } = renderHook(() => usePositionReporting('ASSIGNED'))
    expect(result.current.isReporting).toBe(false)
    expect(geo.watchPosition).not.toHaveBeenCalled()
  })

  it('does not start watching when state is ON_HOLD', () => {
    const { result } = renderHook(() => usePositionReporting('ON_HOLD'))
    expect(result.current.isReporting).toBe(false)
  })

  it('stops watching when state transitions from EN_ROUTE to COMPLETED', () => {
    const { result, rerender } = renderHook(
      ({ state }) => usePositionReporting(state),
      { initialProps: { state: 'EN_ROUTE' } }
    )
    expect(result.current.isReporting).toBe(true)

    rerender({ state: 'COMPLETED' })
    expect(result.current.isReporting).toBe(false)
    expect(geo.clearWatch).toHaveBeenCalled()
  })

  it('sends a POST when a position arrives', async () => {
    renderHook(() => usePositionReporting('EN_ROUTE'))

    await act(async () => {
      geo._triggerSuccess(makePosition())
    })

    expect(fetchCalls).toHaveLength(1)
    expect(fetchCalls[0].url).toBe('/api/v1/technicians/me/position')
    expect(fetchCalls[0].opts.method).toBe('POST')
  })

  it('throttles: second position within 60 seconds is not sent', async () => {
    vi.useFakeTimers()
    renderHook(() => usePositionReporting('EN_ROUTE'))

    await act(async () => {
      geo._triggerSuccess(makePosition())
    })

    // Second position immediately after — should be throttled
    await act(async () => {
      geo._triggerSuccess(makePosition())
    })

    expect(fetchCalls).toHaveLength(1)

    // Advance past the throttle window
    act(() => { vi.advanceTimersByTime(61_000) })

    await act(async () => {
      geo._triggerSuccess(makePosition())
    })

    expect(fetchCalls).toHaveLength(2)
  })

  it('PERMISSION_DENIED stops reporting and sets geoError', async () => {
    const { result } = renderHook(() => usePositionReporting('EN_ROUTE'))

    await act(async () => {
      geo._triggerError({ code: 1, message: 'User denied Geolocation' })
    })

    expect(result.current.isReporting).toBe(false)
    expect(result.current.geoError).toBe('location_denied')
  })

  it('POSITION_UNAVAILABLE (code 2) does not set geoError or stop reporting', async () => {
    const { result } = renderHook(() => usePositionReporting('EN_ROUTE'))

    await act(async () => {
      geo._triggerError({ code: 2, message: 'Position unavailable' })
    })

    expect(result.current.geoError).toBeNull()
    expect(result.current.isReporting).toBe(true)
  })

  it('stops watching on unmount', () => {
    const { unmount } = renderHook(() => usePositionReporting('EN_ROUTE'))
    expect(geo.watchPosition).toHaveBeenCalledOnce()

    unmount()
    expect(geo.clearWatch).toHaveBeenCalled()
  })

  it('is a no-op when geolocation API is not available', () => {
    vi.stubGlobal('navigator', {})
    const { result } = renderHook(() => usePositionReporting('EN_ROUTE'))
    expect(result.current.isReporting).toBe(false)
    expect(fetchCalls).toHaveLength(0)
  })

  it('send body contains lat/lon/accuracy/capturedAt and no PII in URL', async () => {
    renderHook(() => usePositionReporting('EN_ROUTE'))

    await act(async () => {
      geo._triggerSuccess(makePosition(48.8566, 2.3522, 15))
    })

    expect(fetchCalls).toHaveLength(1)
    const body = JSON.parse(fetchCalls[0].opts.body)
    expect(body.latitude).toBe(48.8566)
    expect(body.longitude).toBe(2.3522)
    expect(body.accuracyMetres).toBe(15)
    expect(body.capturedAt).toBeDefined()
    // URL must not contain coordinates
    expect(fetchCalls[0].url).not.toContain('48.8566')
  })
})
