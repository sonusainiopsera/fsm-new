/**
 * @fileoverview Unit tests for useFieldErrors hook.
 */
import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useFieldErrors } from './useFieldErrors.js'

function make400Error(fieldErrors = [], message = 'Validation failed', traceId = null) {
  const err = new Error(message)
  err.status = 400
  err.fieldErrors = fieldErrors
  if (traceId) err.traceId = traceId
  return err
}

describe('useFieldErrors', () => {
  it('returns empty state when error is null', () => {
    const { result } = renderHook(() => useFieldErrors(null, ['name']))
    expect(result.current.summaryErrors).toHaveLength(0)
    expect(result.current.traceId).toBeNull()
    expect(result.current.getFieldErrors('name')).toHaveLength(0)
  })

  it('maps known field errors to getFieldErrors', () => {
    const err = make400Error([{ field: 'name', message: 'must not be blank' }])
    const { result } = renderHook(() => useFieldErrors(err, ['name']))
    expect(result.current.getFieldErrors('name')).toEqual(['must not be blank'])
    expect(result.current.summaryErrors).toHaveLength(0)
  })

  it('puts unknown field errors in summaryErrors', () => {
    const err = make400Error([{ field: 'unknownField', message: 'invalid' }])
    const { result } = renderHook(() => useFieldErrors(err, ['name']))
    expect(result.current.summaryErrors).toHaveLength(1)
    expect(result.current.summaryErrors[0].message).toContain('invalid')
    expect(result.current.getFieldErrors('name')).toHaveLength(0)
  })

  it('exposes the traceId from the error', () => {
    const err = make400Error([{ field: 'name', message: 'required' }], 'Validation failed', 'trace-abc-123')
    err.traceId = 'trace-abc-123'
    const { result } = renderHook(() => useFieldErrors(err, ['name']))
    expect(result.current.traceId).toBe('trace-abc-123')
  })

  it('puts error message in summaryErrors when status=400 and no fieldErrors', () => {
    const err = make400Error([], 'Unexpected validation error')
    const { result } = renderHook(() => useFieldErrors(err, ['name']))
    expect(result.current.summaryErrors).toHaveLength(1)
    expect(result.current.summaryErrors[0].message).toBe('Unexpected validation error')
  })

  it('handles multiple field errors for the same known field', () => {
    const err = make400Error([
      { field: 'name', message: 'must not be blank' },
      { field: 'name', message: 'must have at least 2 characters' },
    ])
    const { result } = renderHook(() => useFieldErrors(err, ['name']))
    expect(result.current.getFieldErrors('name')).toHaveLength(2)
  })

  it('separates known-field errors from unknown-field errors', () => {
    const err = make400Error([
      { field: 'name', message: 'required' },
      { field: 'unmappedField', message: 'bad value' },
    ])
    const { result } = renderHook(() => useFieldErrors(err, ['name']))
    expect(result.current.getFieldErrors('name')).toHaveLength(1)
    expect(result.current.summaryErrors).toHaveLength(1)
  })
})
