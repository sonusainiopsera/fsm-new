/**
 * Unit tests for queryClient.js — retry predicate and backoff.
 */

import { describe, it, expect } from 'vitest'
import { retryPredicate, retryDelay } from './queryClient.js'

describe('retryPredicate', () => {
  it.each([400, 401, 403, 404, 409, 422, 429])(
    'returns false for 4xx status %d regardless of failure count',
    (status) => {
      const err = { status, retryable: false }
      expect(retryPredicate(0, err)).toBe(false)
      expect(retryPredicate(1, err)).toBe(false)
    }
  )

  it('returns false for 409 (illegal transition) — must not be retried', () => {
    expect(retryPredicate(0, { status: 409, retryable: false })).toBe(false)
  })

  it('returns false for 422 (guard refusal) — must not be retried', () => {
    expect(retryPredicate(0, { status: 422, retryable: false })).toBe(false)
  })

  it('returns false for 429 (rate limited) — must not be retried', () => {
    expect(retryPredicate(0, { status: 429, retryable: false })).toBe(false)
  })

  it('returns true for 500 on first attempt', () => {
    expect(retryPredicate(0, { status: 500, retryable: true })).toBe(true)
  })

  it('returns true for 503 on first attempt', () => {
    expect(retryPredicate(0, { status: 503, retryable: true })).toBe(true)
  })

  it('returns false after MAX_RETRY_ATTEMPTS (3) even for 5xx', () => {
    expect(retryPredicate(3, { status: 500, retryable: true })).toBe(false)
  })

  it('returns true for network error (status 0)', () => {
    expect(retryPredicate(0, { status: 0, retryable: true })).toBe(true)
  })
})

describe('retryDelay', () => {
  it('increases with attempt number', () => {
    // Remove jitter by checking that later attempts produce >= earlier ones
    // (with very high probability due to base exp growth dominating jitter)
    const attempt0 = retryDelay(0)
    const attempt2 = retryDelay(2)
    // Attempt 0: 500 + jitter; attempt 2: 2000 + jitter — always larger
    expect(attempt2).toBeGreaterThan(attempt0)
  })

  it('is capped at MAX_DELAY_MS (30000)', () => {
    expect(retryDelay(20)).toBeLessThanOrEqual(30_000)
  })

  it('is positive', () => {
    for (let i = 0; i < 5; i++) {
      expect(retryDelay(i)).toBeGreaterThan(0)
    }
  })
})
