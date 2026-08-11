/**
 * @fileoverview Unit tests for useWorkOrderSearch hook.
 *
 * Tests: serialiseFilters, buildSearchUrl, URL construction correctness.
 * The full fetch/query lifecycle is covered by the board page integration test.
 */
import { describe, it, expect } from 'vitest'
import { serialiseFilters, buildSearchUrl } from '../api/useWorkOrderSearch.js'

describe('serialiseFilters', () => {
  it('returns empty string when no filters set', () => {
    expect(serialiseFilters({})).toBe('')
  })

  it('serialises state array as comma-joined param', () => {
    const qs = serialiseFilters({ states: ['NEW', 'ASSIGNED'] })
    expect(qs).toContain('state=NEW%2CASSIGNED')
  })

  it('serialises priority array', () => {
    const qs = serialiseFilters({ priorities: ['CRITICAL', 'HIGH'] })
    expect(qs).toContain('priority=CRITICAL%2CHIGH')
  })

  it('serialises atRisk flag as "true" string', () => {
    const qs = serialiseFilters({ atRisk: true })
    expect(qs).toContain('atRisk=true')
  })

  it('omits atRisk when falsy', () => {
    const qs = serialiseFilters({ atRisk: false })
    expect(qs).not.toContain('atRisk')
  })

  it('serialises date range', () => {
    const qs = serialiseFilters({ dateFrom: '2026-08-01', dateTo: '2026-08-31' })
    expect(qs).toContain('dateFrom=2026-08-01')
    expect(qs).toContain('dateTo=2026-08-31')
  })

  it('omits empty states array', () => {
    const qs = serialiseFilters({ states: [] })
    expect(qs).not.toContain('state')
  })
})

describe('buildSearchUrl', () => {
  it('includes page and size params', () => {
    const url = buildSearchUrl({ page: 0, size: 20 }, {})
    expect(url).toContain('page=0')
    expect(url).toContain('size=20')
  })

  it('includes sort param when provided', () => {
    const url = buildSearchUrl({ page: 0, size: 20, sort: 'priority:DESC' }, {})
    expect(url).toContain('sort=priority%3ADESC')
  })

  it('appends filter params after page params', () => {
    const url = buildSearchUrl(
      { page: 0, size: 20 },
      { states: ['NEW'], atRisk: true },
    )
    expect(url).toContain('state=NEW')
    expect(url).toContain('atRisk=true')
  })

  it('returns base path with no extra separators when no params', () => {
    const url = buildSearchUrl({ page: 0, size: 20 }, {})
    expect(url).toMatch(/^\/api\/v1\/work-orders\?/)
  })
})
