/**
 * Unit tests for stateMapping.js — error-to-named-state mapping.
 */

import { describe, it, expect } from 'vitest'
import { mapErrorToState, mapQueryToState } from './stateMapping.js'

const makeError = (status, overrides = {}) => ({
  status,
  code: `CODE_${status}`,
  message: `Error ${status}`,
  fieldErrors: [],
  traceId: null,
  retryable: status >= 500,
  ...overrides,
})

describe('mapErrorToState', () => {
  it('maps 403 to permission-denied', () => {
    expect(mapErrorToState(makeError(403)).variant).toBe('permission-denied')
  })

  it('maps 401 to permission-denied', () => {
    expect(mapErrorToState(makeError(401)).variant).toBe('permission-denied')
  })

  it('does not disclose existence in 403 (message is generic, no resource id)', () => {
    const mapping = mapErrorToState(makeError(403))
    expect(mapping.variant).toBe('permission-denied')
    expect(mapping.message).toBeUndefined()
  })

  it('maps 429 to degraded with retryAfterMs', () => {
    const mapping = mapErrorToState(makeError(429), { retryAfterMs: 30_000 })
    expect(mapping.variant).toBe('degraded')
    expect(mapping.retryAfterMs).toBe(30_000)
  })

  it('maps 503 to degraded', () => {
    expect(mapErrorToState(makeError(503)).variant).toBe('degraded')
  })

  it('maps 409 to error with server message surfaced verbatim', () => {
    const err = makeError(409, { message: 'Illegal state transition.' })
    const mapping = mapErrorToState(err)
    expect(mapping.variant).toBe('error')
    expect(mapping.message).toBe('Illegal state transition.')
  })

  it('maps 422 to error with server message surfaced verbatim', () => {
    const err = makeError(422, { message: 'Insufficient stock for one or more lines.' })
    const mapping = mapErrorToState(err)
    expect(mapping.variant).toBe('error')
    expect(mapping.message).toBe('Insufficient stock for one or more lines.')
  })

  it('maps network error (status 0) to error with connectivity message', () => {
    const mapping = mapErrorToState(makeError(0))
    expect(mapping.variant).toBe('error')
    expect(mapping.message).toContain('network')
  })

  it('maps 500 to generic error variant', () => {
    expect(mapErrorToState(makeError(500)).variant).toBe('error')
  })
})

describe('mapQueryToState', () => {
  it('returns loading when fetching and no data yet', () => {
    expect(mapQueryToState({ isFetching: true, isStale: false, dataUpdatedAt: 0 })).toBe('loading')
  })

  it('returns null when data is fresh and not fetching', () => {
    expect(mapQueryToState({ isFetching: false, isStale: false, dataUpdatedAt: Date.now() })).toBeNull()
  })

  it('returns degraded when data is stale beyond the staleness window', () => {
    const oldTime = Date.now() - 200_000  // 200 s ago > 120 s default
    expect(mapQueryToState({ isFetching: false, isStale: true, dataUpdatedAt: oldTime })).toBe('degraded')
  })

  it('returns null when stale but within the window', () => {
    const recentTime = Date.now() - 30_000  // 30 s ago < 120 s default
    expect(mapQueryToState({ isFetching: false, isStale: true, dataUpdatedAt: recentTime })).toBeNull()
  })
})
