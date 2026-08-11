/**
 * @fileoverview Unit tests for mapTransitionError — the typed error mapping in
 * useWorkOrderTransition.
 */
import { describe, it, expect } from 'vitest'
import { mapTransitionError } from '../api/useWorkOrderTransition.js'

/** Helper to build a minimal ClientError-like object. */
function makeError(status, code, message) {
  return { status, code, message }
}

describe('mapTransitionError', () => {
  it('maps 409 WORK_ORDER_VERSION_CONFLICT to version_conflict', () => {
    const err = makeError(409, 'WORK_ORDER_VERSION_CONFLICT', 'conflict')
    const result = mapTransitionError(err)
    expect(result.variant).toBe('version_conflict')
    expect(result.message).toMatch(/reload/i)
  })

  it('maps 409 other code to illegal_transition', () => {
    const err = makeError(409, 'WORK_ORDER_ILLEGAL_TRANSITION', 'illegal')
    const result = mapTransitionError(err)
    expect(result.variant).toBe('illegal_transition')
    expect(result.message).toMatch(/no longer available/i)
  })

  it('maps 422 to guard_refused and surfaces server message', () => {
    const err = makeError(422, 'WORK_ORDER_GUARD_REFUSED', 'Technician not certified')
    const result = mapTransitionError(err)
    expect(result.variant).toBe('guard_refused')
    expect(result.message).toBe('Technician not certified')
  })

  it('maps 403 to permission variant', () => {
    const err = makeError(403, 'FORBIDDEN', 'Forbidden')
    const result = mapTransitionError(err)
    expect(result.variant).toBe('permission')
    expect(result.message).toMatch(/permission/i)
  })

  it('maps unknown status to unknown variant', () => {
    const err = makeError(503, 'SERVICE_UNAVAILABLE', 'Down')
    const result = mapTransitionError(err)
    expect(result.variant).toBe('unknown')
  })

  it('preserves the raw error on all variants', () => {
    const err = makeError(409, 'WORK_ORDER_VERSION_CONFLICT', 'conflict')
    const result = mapTransitionError(err)
    expect(result.raw).toBe(err)
  })
})
