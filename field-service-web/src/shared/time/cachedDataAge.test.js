/**
 * Unit tests for cachedDataAge formatter.
 */
import { describe, it, expect } from 'vitest'
import { formatCachedAge, isCacheExpired, SHIFT_TTL_MS } from './cachedDataAge.js'

describe('formatCachedAge', () => {
  it('returns "just now" for age under 1 minute', () => {
    expect(formatCachedAge(0)).toBe('just now')
    expect(formatCachedAge(30_000)).toBe('just now')
    expect(formatCachedAge(59_999)).toBe('just now')
  })

  it('returns singular minute for exactly 1 minute', () => {
    expect(formatCachedAge(60_000)).toBe('1 minute ago')
  })

  it('returns plural minutes for 2-59 minutes', () => {
    expect(formatCachedAge(2 * 60_000)).toBe('2 minutes ago')
    expect(formatCachedAge(45 * 60_000)).toBe('45 minutes ago')
    expect(formatCachedAge(59 * 60_000)).toBe('59 minutes ago')
  })

  it('returns singular hour for exactly 1 hour', () => {
    expect(formatCachedAge(60 * 60_000)).toBe('1 hour ago')
  })

  it('returns plural hours for 2+ hours', () => {
    expect(formatCachedAge(2 * 60 * 60_000)).toBe('2 hours ago')
    expect(formatCachedAge(11 * 60 * 60_000)).toBe('11 hours ago')
  })

  it('returns "unknown" for negative age', () => {
    expect(formatCachedAge(-1)).toBe('unknown')
  })
})

describe('isCacheExpired', () => {
  it('returns false when age is below shift TTL', () => {
    expect(isCacheExpired(0)).toBe(false)
    expect(isCacheExpired(SHIFT_TTL_MS - 1)).toBe(false)
  })

  it('returns true when age equals or exceeds shift TTL (12 hours)', () => {
    expect(isCacheExpired(SHIFT_TTL_MS)).toBe(true)
    expect(isCacheExpired(SHIFT_TTL_MS + 1)).toBe(true)
  })
})
