/**
 * Unit tests for pagination.js — page size clamping, envelope parsing.
 */

import { describe, it, expect } from 'vitest'
import {
  buildPageQuery, toQueryString, parsePagedEnvelope,
  extractPageMeta, extractLinks, extractData, MAX_PAGE_SIZE,
} from './pagination.js'

describe('buildPageQuery — size clamping', () => {
  it('clamps size above 50 to 50', () => {
    expect(buildPageQuery({ size: 100 }).size).toBe(50)
    expect(buildPageQuery({ size: 51 }).size).toBe(50)
  })

  it('passes size 50 through unchanged', () => {
    expect(buildPageQuery({ size: 50 }).size).toBe(50)
  })

  it('clamps size below 1 to 1', () => {
    expect(buildPageQuery({ size: 0 }).size).toBe(1)
    expect(buildPageQuery({ size: -10 }).size).toBe(1)
  })

  it('defaults to 20 when no size provided', () => {
    expect(buildPageQuery().size).toBe(20)
  })

  it('clamps page below 0 to 0', () => {
    expect(buildPageQuery({ page: -1 }).page).toBe(0)
  })

  it('passes sort through', () => {
    expect(buildPageQuery({ sort: 'createdAt' }).sort).toBe('createdAt')
  })

  it('passes cursor through', () => {
    const q = buildPageQuery({ cursor: 'abc123' })
    expect(q.cursor).toBe('abc123')
  })

  it('MAX_PAGE_SIZE is 50', () => {
    expect(MAX_PAGE_SIZE).toBe(50)
  })
})

describe('toQueryString', () => {
  it('produces correct query string', () => {
    const qs = toQueryString({ page: 2, size: 20, sort: 'createdAt' })
    expect(qs).toContain('page=2')
    expect(qs).toContain('size=20')
    expect(qs).toContain('sort=createdAt')
  })

  it('omits cursor when not present', () => {
    const qs = toQueryString({ page: 0, size: 10 })
    expect(qs).not.toContain('cursor')
  })
})

describe('parsePagedEnvelope', () => {
  const envelope = {
    data: [{ id: 'a' }, { id: 'b' }],
    page: { totalElements: 12, totalPages: 3, page: 0, size: 5, hasNext: true, hasPrev: false },
    links: { self: '/items?page=0', next: '/items?page=1', prev: null },
  }

  it('extracts data array', () => {
    const result = parsePagedEnvelope(envelope)
    expect(result.data).toHaveLength(2)
  })

  it('extracts page metadata correctly', () => {
    const result = parsePagedEnvelope(envelope)
    expect(result.page.totalElements).toBe(12)
    expect(result.page.hasNext).toBe(true)
    expect(result.page.hasPrev).toBe(false)
  })

  it('extracts links', () => {
    const result = parsePagedEnvelope(envelope)
    expect(result.links.next).toBe('/items?page=1')
    expect(result.links.prev).toBeNull()
  })

  it('returns empty data for missing data field', () => {
    expect(parsePagedEnvelope({}).data).toEqual([])
    expect(parsePagedEnvelope(null).data).toEqual([])
  })

  it('returns safe defaults for missing page metadata', () => {
    const meta = extractPageMeta({})
    expect(meta.totalElements).toBe(0)
    expect(meta.hasNext).toBe(false)
  })
})
