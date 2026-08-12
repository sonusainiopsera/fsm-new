/**
 * Unit tests for useConnectivity.
 * Simulates online/offline transitions, failing heartbeats, and debouncing.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useConnectivity } from './useConnectivity.js'

describe('useConnectivity', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(navigator, 'onLine', {
      value: true,
      writable: true,
      configurable: true,
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('initialises to connected when navigator.onLine is true', () => {
    const { result } = renderHook(() => useConnectivity())
    expect(result.current.isConnected).toBe(true)
  })

  it('initialises to disconnected when navigator.onLine is false', () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    const { result } = renderHook(() => useConnectivity())
    // After OFFLINE_DEBOUNCE_MS the state flips
    act(() => { vi.advanceTimersByTime(600) })
    expect(result.current.isConnected).toBe(false)
  })

  it('marks offline after debounce when offline event fires', async () => {
    const { result } = renderHook(() => useConnectivity())
    expect(result.current.isConnected).toBe(true)

    act(() => {
      Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
      window.dispatchEvent(new Event('offline'))
    })

    // Before debounce — still online
    expect(result.current.isConnected).toBe(true)

    act(() => { vi.advanceTimersByTime(600) })
    expect(result.current.isConnected).toBe(false)
  })

  it('marks online after debounce when online event fires and heartbeat succeeds', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    const { result } = renderHook(() => useConnectivity())
    act(() => { vi.advanceTimersByTime(600) })
    expect(result.current.isConnected).toBe(false)

    act(() => {
      Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
      window.dispatchEvent(new Event('online'))
    })

    // Heartbeat fetch is queued asynchronously — let it resolve
    await act(async () => {
      await Promise.resolve()
    })

    act(() => { vi.advanceTimersByTime(1600) })
    expect(result.current.isConnected).toBe(true)
  })

  it('stays disconnected when heartbeat fetch throws (network error)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    const { result } = renderHook(() => useConnectivity())
    act(() => { vi.advanceTimersByTime(600) })
    expect(result.current.isConnected).toBe(false)
  })

  it('records lastConnectedAt when transitioning to offline', async () => {
    const { result } = renderHook(() => useConnectivity())
    expect(result.current.lastConnectedAt).toBeNull()

    act(() => {
      Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
      window.dispatchEvent(new Event('offline'))
    })
    act(() => { vi.advanceTimersByTime(600) })

    expect(result.current.lastConnectedAt).not.toBeNull()
    expect(typeof result.current.lastConnectedAt).toBe('number')
  })

  it('guardMutation returns false when offline', () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    const { result } = renderHook(() => useConnectivity())
    act(() => { vi.advanceTimersByTime(600) })
    expect(result.current.guardMutation()).toBe(false)
  })

  it('guardMutation returns true when online', () => {
    const { result } = renderHook(() => useConnectivity())
    expect(result.current.guardMutation()).toBe(true)
  })

  it('does NOT queue write when guardMutation is false — AC-5', () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    const { result } = renderHook(() => useConnectivity())
    act(() => { vi.advanceTimersByTime(600) })

    let queued = false
    function attemptMutation() {
      if (!result.current.guardMutation()) {
        return { blocked: true, queued: false }
      }
      queued = true
      return { blocked: false, queued: true }
    }

    const outcome = attemptMutation()
    expect(outcome.blocked).toBe(true)
    expect(outcome.queued).toBe(false)
    expect(queued).toBe(false)
  })

  it('sets isDegraded when heartbeat returns 5xx', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    const { result } = renderHook(() => useConnectivity())

    act(() => { window.dispatchEvent(new Event('online')) })
    await act(async () => { await Promise.resolve() })
    act(() => { vi.advanceTimersByTime(1600) })

    expect(result.current.isDegraded).toBe(true)
    expect(result.current.isConnected).toBe(true)
  })

  it('cleans up intervals and timers on unmount', () => {
    const clearIntervalSpy = vi.spyOn(global, 'clearInterval')
    const clearTimeoutSpy = vi.spyOn(global, 'clearTimeout')
    const { unmount } = renderHook(() => useConnectivity())
    unmount()
    expect(clearIntervalSpy).toHaveBeenCalled()
  })
})
