/**
 * Unit tests for http.js — 401 single-flight refresh, idempotency key, 304 handling.
 *
 * AC-3: Ten concurrent 401 responses produce exactly one refresh call.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as tokenStore from './tokenStore.js'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function buildJsonResponse(status, body = {}, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function buildErrorEnvelope(code = 'VALIDATION_FAILED', message = 'bad request') {
  return { code, message, fieldErrors: [], traceId: null }
}

// ---------------------------------------------------------------------------
// Setup — intercept fetch globally
// ---------------------------------------------------------------------------

let fetchMock
let refreshCallCount

beforeEach(() => {
  refreshCallCount = 0
  vi.stubGlobal('fetch', (...args) => fetchMock(...args))
  tokenStore.setToken('initial-token-abc')
})

afterEach(() => {
  tokenStore._resetForTesting()
  vi.restoreAllMocks()
})

// ---------------------------------------------------------------------------
// Import http module AFTER stub is in place
// ---------------------------------------------------------------------------

const { request } = await import('./http.js')

// ---------------------------------------------------------------------------
// 401 single-flight refresh (AC-3)
// ---------------------------------------------------------------------------

describe('401 single-flight refresh', () => {
  it('ten concurrent 401s produce exactly one refresh call', async () => {
    let refreshCallCount = 0

    // First round of requests: all return 401
    // Auth refresh endpoint: returns new token
    fetchMock = vi.fn((url, opts) => {
      if (url.includes('/auth/refresh')) {
        refreshCallCount++
        return Promise.resolve(
          buildJsonResponse(200, { accessToken: 'refreshed-token-xyz' })
        )
      }
      // First call per request: 401
      // After refresh, return 200
      const token = opts?.headers?.Authorization
      if (token === 'Bearer refreshed-token-xyz') {
        return Promise.resolve(buildJsonResponse(200, { id: 'ok' }))
      }
      return Promise.resolve(
        buildJsonResponse(401, buildErrorEnvelope('UNAUTHENTICATED', 'Token expired'))
      )
    })

    const CONCURRENCY = 10
    const promises = Array.from({ length: CONCURRENCY }, () =>
      request('/work-orders', { method: 'GET' }).catch(() => null)
    )

    await Promise.allSettled(promises)

    expect(refreshCallCount).toBe(1)
  })

  it('clears token and does not retry when refresh itself returns 401', async () => {
    fetchMock = vi.fn((url) => {
      // Both the original request and the refresh endpoint return 401
      return Promise.resolve(
        buildJsonResponse(401, buildErrorEnvelope('UNAUTHENTICATED', 'Refresh expired'))
      )
    })

    const signOutCb = vi.fn()
    tokenStore.onSignOut(signOutCb)

    await request('/work-orders', { method: 'GET' }).catch(() => null)

    expect(tokenStore.getToken()).toBeNull()
    expect(signOutCb).toHaveBeenCalled()
  })
})

// ---------------------------------------------------------------------------
// Idempotency-Key injection
// ---------------------------------------------------------------------------

describe('Idempotency-Key header', () => {
  it('injects a header on POST requests', async () => {
    const capturedHeaders = {}
    fetchMock = vi.fn((url, opts) => {
      Object.assign(capturedHeaders, opts?.headers ?? {})
      return Promise.resolve(buildJsonResponse(201, { id: 'new' }))
    })

    await request('/work-orders', { method: 'POST', body: '{}' })

    expect(capturedHeaders['Idempotency-Key']).toBeDefined()
    expect(typeof capturedHeaders['Idempotency-Key']).toBe('string')
    expect(capturedHeaders['Idempotency-Key'].length).toBeGreaterThan(0)
  })

  it('injects a header on PUT requests', async () => {
    const capturedHeaders = {}
    fetchMock = vi.fn((url, opts) => {
      Object.assign(capturedHeaders, opts?.headers ?? {})
      return Promise.resolve(buildJsonResponse(200, { id: 'updated' }))
    })

    await request('/work-orders/123', { method: 'PUT', body: '{}' })

    expect(capturedHeaders['Idempotency-Key']).toBeDefined()
  })

  it('does not inject Idempotency-Key on GET requests', async () => {
    const capturedHeaders = {}
    fetchMock = vi.fn((url, opts) => {
      Object.assign(capturedHeaders, opts?.headers ?? {})
      return Promise.resolve(buildJsonResponse(200, { items: [] }))
    })

    await request('/work-orders', { method: 'GET' })

    expect(capturedHeaders['Idempotency-Key']).toBeUndefined()
  })

  it('two separate POST calls produce different Idempotency-Keys', async () => {
    const keys = []
    fetchMock = vi.fn((url, opts) => {
      keys.push(opts?.headers?.['Idempotency-Key'])
      return Promise.resolve(buildJsonResponse(201, { id: 'new' }))
    })

    await request('/work-orders', { method: 'POST', body: '{}' })
    await request('/work-orders', { method: 'POST', body: '{}' })

    expect(keys[0]).toBeDefined()
    expect(keys[1]).toBeDefined()
    expect(keys[0]).not.toBe(keys[1])
  })
})

// ---------------------------------------------------------------------------
// 304 Not Modified
// ---------------------------------------------------------------------------

describe('304 Not Modified', () => {
  it('returns __notModified sentinel on 304 response', async () => {
    fetchMock = vi.fn(() => Promise.resolve(new Response(null, { status: 304 })))

    const result = await request('/work-orders', {
      method: 'GET',
      headers: { 'If-None-Match': '"etag-abc"' },
    })

    expect(result).toEqual({ __notModified: true })
  })
})

// ---------------------------------------------------------------------------
// Auth header injection
// ---------------------------------------------------------------------------

describe('Authorization header', () => {
  it('injects Bearer token from tokenStore on non-auth requests', async () => {
    const capturedHeaders = {}
    fetchMock = vi.fn((url, opts) => {
      Object.assign(capturedHeaders, opts?.headers ?? {})
      return Promise.resolve(buildJsonResponse(200, { data: [] }))
    })

    tokenStore.setToken('my-access-token')
    await request('/work-orders', { method: 'GET' })

    expect(capturedHeaders['Authorization']).toBe('Bearer my-access-token')
  })

  it('does not inject Authorization when no token is set', async () => {
    tokenStore.clearToken()
    const capturedHeaders = {}
    fetchMock = vi.fn((url, opts) => {
      Object.assign(capturedHeaders, opts?.headers ?? {})
      return Promise.resolve(buildJsonResponse(200, { data: [] }))
    })

    await request('/work-orders', { method: 'GET' }).catch(() => null)

    expect(capturedHeaders['Authorization']).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// 4xx error handling — retryable=false
// ---------------------------------------------------------------------------

describe('4xx error handling', () => {
  it.each([400, 403, 404, 409, 422, 429])('throws ClientError with retryable=false for %i', async (status) => {
    fetchMock = vi.fn(() =>
      Promise.resolve(buildJsonResponse(status, buildErrorEnvelope('CODE', 'msg')))
    )

    const error = await request('/anything', { method: 'GET' }).catch(e => e)

    expect(error).toBeTruthy()
    expect(error.retryable).toBe(false)
    expect(error.status).toBe(status)
  })
})
