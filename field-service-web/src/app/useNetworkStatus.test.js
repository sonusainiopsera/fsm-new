/**
 * Unit tests for useNetworkStatus.
 * Tests run without react-router-dom — uses renderHook from testing-library.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useNetworkStatus } from './useNetworkStatus.js'

describe('useNetworkStatus', () => {
  const originalOnLine = Object.getOwnPropertyDescriptor(navigator, 'onLine')

  afterEach(() => {
    if (originalOnLine) {
      Object.defineProperty(navigator, 'onLine', originalOnLine)
    }
  })

  function setOnline(value) {
    Object.defineProperty(navigator, 'onLine', {
      value,
      writable: true,
      configurable: true,
    })
  }

  it('returns isOnline=true when navigator.onLine is true', () => {
    setOnline(true)
    const { result } = renderHook(() => useNetworkStatus())
    expect(result.current.isOnline).toBe(true)
  })

  it('returns isOnline=false when navigator.onLine is false', () => {
    setOnline(false)
    const { result } = renderHook(() => useNetworkStatus())
    expect(result.current.isOnline).toBe(false)
  })

  it('updates to offline when offline event fires', () => {
    setOnline(true)
    const { result } = renderHook(() => useNetworkStatus())
    expect(result.current.isOnline).toBe(true)

    act(() => {
      setOnline(false)
      window.dispatchEvent(new Event('offline'))
    })

    expect(result.current.isOnline).toBe(false)
  })

  it('updates to online when online event fires', () => {
    setOnline(false)
    const { result } = renderHook(() => useNetworkStatus())
    expect(result.current.isOnline).toBe(false)

    act(() => {
      setOnline(true)
      window.dispatchEvent(new Event('online'))
    })

    expect(result.current.isOnline).toBe(true)
  })

  it('guardMutation returns true when online', () => {
    setOnline(true)
    const { result } = renderHook(() => useNetworkStatus())
    expect(result.current.guardMutation()).toBe(true)
  })

  it('guardMutation returns false when offline — callers must NOT queue writes', () => {
    setOnline(false)
    const { result } = renderHook(() => useNetworkStatus())
    expect(result.current.guardMutation()).toBe(false)
  })

  it('removes event listeners on unmount', () => {
    setOnline(true)
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const { unmount } = renderHook(() => useNetworkStatus())
    unmount()
    expect(removeSpy).toHaveBeenCalledWith('online', expect.any(Function))
    expect(removeSpy).toHaveBeenCalledWith('offline', expect.any(Function))
    removeSpy.mockRestore()
  })
})

describe('useNetworkStatus — offline mutation guard semantics', () => {
  it('a false guardMutation result means caller must refuse the write', () => {
    Object.defineProperty(navigator, 'onLine', { value: false, writable: true, configurable: true })
    const { result } = renderHook(() => useNetworkStatus())

    // Simulate a mutation path that checks the guard
    let writeAttempted = false
    function attemptWrite() {
      if (!result.current.guardMutation()) {
        return { blocked: true, queued: false }
      }
      writeAttempted = true
      return { blocked: false, queued: false }
    }

    const outcome = attemptWrite()
    expect(outcome.blocked).toBe(true)
    expect(outcome.queued).toBe(false) // AC-8: no queueing
    expect(writeAttempted).toBe(false)
  })
})
