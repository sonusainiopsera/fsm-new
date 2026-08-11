/**
 * @fileoverview Unit tests for pure utility functions exported from useSignIn.js.
 *
 * These test the pure helper exports without rendering any React component so
 * they run without a DOM environment.
 */

import { describe, it, expect } from 'vitest'

import {
  landingRouteForRoles,
  validateLoginResponse,
  parseJwtPayload,
} from '../useSignIn.js'

// ── landingRouteForRoles ──────────────────────────────────────────────────────

describe('landingRouteForRoles', () => {
  it('returns /field for TECHNICIAN', () => {
    expect(landingRouteForRoles(['TECHNICIAN'])).toBe('/field')
  })

  it('returns /portal for CUSTOMER', () => {
    expect(landingRouteForRoles(['CUSTOMER'])).toBe('/portal')
  })

  it('returns /operations for MANAGER', () => {
    expect(landingRouteForRoles(['MANAGER'])).toBe('/operations')
  })

  it('returns /dispatch for unknown roles', () => {
    expect(landingRouteForRoles(['DISPATCHER'])).toBe('/dispatch')
  })

  it('returns /dispatch for empty roles', () => {
    expect(landingRouteForRoles([])).toBe('/dispatch')
  })

  it('prioritises TECHNICIAN when multiple roles present', () => {
    expect(landingRouteForRoles(['TECHNICIAN', 'MANAGER'])).toBe('/field')
  })

  it('prioritises CUSTOMER over MANAGER when TECHNICIAN absent', () => {
    expect(landingRouteForRoles(['CUSTOMER', 'MANAGER'])).toBe('/portal')
  })
})

// ── validateLoginResponse ─────────────────────────────────────────────────────

describe('validateLoginResponse', () => {
  const valid = { accessToken: 'tok', roles: ['TECHNICIAN'], userId: 'u1' }

  it('returns the body unchanged for a valid response', () => {
    expect(validateLoginResponse(valid)).toBe(valid)
  })

  it('throws TypeError when body is null', () => {
    expect(() => validateLoginResponse(null)).toThrow(TypeError)
  })

  it('throws TypeError when accessToken is missing', () => {
    expect(() => validateLoginResponse({ ...valid, accessToken: undefined })).toThrow(TypeError)
  })

  it('throws TypeError when accessToken is empty string', () => {
    expect(() => validateLoginResponse({ ...valid, accessToken: '' })).toThrow(TypeError)
  })

  it('throws TypeError when roles is not an array', () => {
    expect(() => validateLoginResponse({ ...valid, roles: 'TECHNICIAN' })).toThrow(TypeError)
  })

  it('throws TypeError when userId is missing', () => {
    expect(() => validateLoginResponse({ ...valid, userId: undefined })).toThrow(TypeError)
  })

  it('throws TypeError when userId is not a string', () => {
    expect(() => validateLoginResponse({ ...valid, userId: 42 })).toThrow(TypeError)
  })
})

// ── parseJwtPayload ───────────────────────────────────────────────────────────

describe('parseJwtPayload', () => {
  function makeToken(payload) {
    const encoded = btoa(JSON.stringify(payload))
      .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
    return `header.${encoded}.sig`
  }

  it('returns the decoded payload for a valid JWT', () => {
    const payload = { sub: 'u1', roles: ['TECHNICIAN'], exp: 9999999999 }
    expect(parseJwtPayload(makeToken(payload))).toEqual(payload)
  })

  it('returns null for a string with fewer than 3 parts', () => {
    expect(parseJwtPayload('only.two')).toBeNull()
  })

  it('returns null for a non-JWT string', () => {
    expect(parseJwtPayload('not-a-jwt')).toBeNull()
  })

  it('returns null for an empty string', () => {
    expect(parseJwtPayload('')).toBeNull()
  })

  it('returns null when payload part is invalid base64', () => {
    expect(parseJwtPayload('header.!!!invalid!!!.sig')).toBeNull()
  })

  it('handles base64url padding correctly', () => {
    // Payload length that requires padding — verify no parse error
    const payload = { sub: 'x' }
    const result = parseJwtPayload(makeToken(payload))
    expect(result).toEqual(payload)
  })
})
