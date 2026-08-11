/**
 * Unit tests for errors.js — error mapper per status code.
 */

import { describe, it, expect } from 'vitest'
import { normaliseError, networkError, isRetryable } from './errors.js'

describe('isRetryable', () => {
  it.each([400, 401, 403, 404, 409, 410, 422, 429])(
    'returns false for %d (4xx must never be retried)',
    (status) => {
      expect(isRetryable(status)).toBe(false)
    }
  )

  it('returns false for 409 (illegal transition)', () => {
    expect(isRetryable(409)).toBe(false)
  })

  it('returns false for 422 (guard refusal)', () => {
    expect(isRetryable(422)).toBe(false)
  })

  it('returns false for 429 (rate limited)', () => {
    expect(isRetryable(429)).toBe(false)
  })

  it.each([500, 502, 503, 504])(
    'returns true for %d (5xx is retryable)',
    (status) => {
      expect(isRetryable(status)).toBe(true)
    }
  )

  it('returns true for 0 (network error)', () => {
    expect(isRetryable(0)).toBe(true)
  })
})

describe('normaliseError', () => {
  it('maps 400 with fieldErrors from the envelope', () => {
    const body = {
      code: 'VALIDATION_FAILED',
      message: 'Validation failed.',
      fieldErrors: [{ field: 'qty', message: 'must be > 0' }],
      traceId: 'trace-abc',
    }
    const err = normaliseError(400, body)
    expect(err.status).toBe(400)
    expect(err.code).toBe('VALIDATION_FAILED')
    expect(err.message).toBe('Validation failed.')
    expect(err.fieldErrors).toHaveLength(1)
    expect(err.fieldErrors[0].field).toBe('qty')
    expect(err.traceId).toBe('trace-abc')
    expect(err.retryable).toBe(false)
  })

  it('maps 403 to FORBIDDEN with retryable=false', () => {
    const err = normaliseError(403, { code: 'FORBIDDEN', message: 'Access denied.', fieldErrors: [], traceId: null })
    expect(err.status).toBe(403)
    expect(err.retryable).toBe(false)
    expect(err.code).toBe('FORBIDDEN')
  })

  it('maps 409 to CONFLICT with retryable=false and preserves message', () => {
    const err = normaliseError(409, { code: 'CONFLICT', message: 'State conflict.', fieldErrors: [], traceId: null })
    expect(err.status).toBe(409)
    expect(err.retryable).toBe(false)
    expect(err.message).toBe('State conflict.')
  })

  it('maps 422 to INSUFFICIENT_STOCK with per-line fieldErrors', () => {
    const body = {
      code: 'INSUFFICIENT_STOCK',
      message: 'Insufficient stock.',
      fieldErrors: [{ field: 'lines[0].quantity', message: 'requested 3, available 2' }],
      traceId: null,
    }
    const err = normaliseError(422, body)
    expect(err.status).toBe(422)
    expect(err.code).toBe('INSUFFICIENT_STOCK')
    expect(err.fieldErrors[0].field).toBe('lines[0].quantity')
    expect(err.retryable).toBe(false)
  })

  it('maps 429 with retryable=false', () => {
    const err = normaliseError(429, { code: 'RATE_LIMITED', message: 'Too many requests.', fieldErrors: [], traceId: null })
    expect(err.retryable).toBe(false)
  })

  it('maps 503 with retryable=true', () => {
    const err = normaliseError(503, { code: 'PROVIDER_DEGRADED', message: 'Unavailable.', fieldErrors: [], traceId: null })
    expect(err.retryable).toBe(true)
  })

  it('uses default code when envelope has no code', () => {
    const err = normaliseError(404, null)
    expect(err.code).toBe('NOT_FOUND')
    expect(err.fieldErrors).toEqual([])
    expect(err.traceId).toBeNull()
  })

  it('surfaces multiple fieldErrors from 400 response', () => {
    const err = normaliseError(400, {
      code: 'VALIDATION_FAILED',
      message: 'Bad',
      fieldErrors: [
        { field: 'name', message: 'required' },
        { field: 'name', message: 'too short' },
        { field: 'email', message: 'invalid' },
      ],
      traceId: null,
    })
    expect(err.fieldErrors).toHaveLength(3)
  })
})

describe('networkError', () => {
  it('creates a retryable error with status 0', () => {
    const err = networkError(new Error('fetch failed'))
    expect(err.status).toBe(0)
    expect(err.retryable).toBe(true)
    expect(err.code).toBe('NETWORK_ERROR')
    expect(err.message).toBe('fetch failed')
  })

  it('handles non-Error cause gracefully', () => {
    const err = networkError('oops')
    expect(err.message).toBe('Network request failed')
  })
})
