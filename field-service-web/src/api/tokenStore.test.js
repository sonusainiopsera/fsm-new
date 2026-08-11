/**
 * Unit tests for tokenStore.js — single-flight refresh and concurrent queuing.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  getToken, setToken, clearToken, refreshToken, isRefreshing,
  signOut, onSignOut, _resetForTesting,
} from './tokenStore.js'

beforeEach(() => {
  _resetForTesting()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('getToken / setToken / clearToken', () => {
  it('starts null', () => {
    expect(getToken()).toBeNull()
  })

  it('stores and retrieves a token', () => {
    setToken('test-access-token')
    expect(getToken()).toBe('test-access-token')
  })

  it('clearToken sets token to null', () => {
    setToken('tok')
    clearToken()
    expect(getToken()).toBeNull()
  })
})

describe('signOut', () => {
  it('clears the token', () => {
    setToken('tok')
    signOut()
    expect(getToken()).toBeNull()
  })

  it('calls the registered onSignOut callback', () => {
    const cb = vi.fn()
    onSignOut(cb)
    signOut()
    expect(cb).toHaveBeenCalledOnce()
  })
})

describe('refreshToken — single-flight guarantee', () => {
  it('calls the refresh endpoint exactly once for N concurrent 401 callers', async () => {
    let resolveRefresh
    const refreshPromise = new Promise(r => { resolveRefresh = r })

    const mockFetch = vi.fn(() => refreshPromise.then(() => ({
      ok: true,
      json: () => Promise.resolve({ accessToken: 'new-token' }),
    })))

    vi.stubGlobal('fetch', mockFetch)

    // Fire 10 concurrent refresh calls
    const CONCURRENT = 10
    const promises = Array.from({ length: CONCURRENT }, () =>
      refreshToken('/api/v1/auth/refresh')
    )

    // Verify only one fetch in flight
    expect(isRefreshing()).toBe(true)
    expect(mockFetch).toHaveBeenCalledTimes(1)

    // Resolve the refresh
    resolveRefresh()
    const results = await Promise.all(promises)

    // All callers receive the new token
    expect(results).toHaveLength(CONCURRENT)
    results.forEach(t => expect(t).toBe('new-token'))

    // Still only one fetch call
    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(getToken()).toBe('new-token')
  })

  it('rejects all queued callers on refresh failure', async () => {
    const mockFetch = vi.fn(() => Promise.resolve({
      ok: false,
      status: 401,
    }))
    vi.stubGlobal('fetch', mockFetch)

    const promises = Array.from({ length: 5 }, () =>
      refreshToken('/api/v1/auth/refresh').catch(e => e)
    )

    const results = await Promise.all(promises)

    results.forEach(r => expect(r).toBeInstanceOf(Error))
    expect(getToken()).toBeNull()
  })

  it('isRefreshing returns false after refresh completes', async () => {
    const mockFetch = vi.fn(() => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ accessToken: 'tok' }),
    }))
    vi.stubGlobal('fetch', mockFetch)

    await refreshToken()
    expect(isRefreshing()).toBe(false)
  })
})

describe('no web storage', () => {
  it('never writes to localStorage', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem')
    setToken('secret-token')
    clearToken()
    setToken('another-token')
    expect(spy).not.toHaveBeenCalled()
  })

  it('never writes to sessionStorage', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem')
    setToken('secret-token')
    expect(spy).not.toHaveBeenCalled()
  })
})
