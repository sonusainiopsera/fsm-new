import { describe, it, expect } from 'vitest';
import {
  buildPageParams,
  buildKeysetParams,
  parsePaginatedResponse,
  MAX_PAGE_SIZE,
} from '../pagination.js';

describe('buildPageParams', () => {
  it('uses defaults when no params provided', () => {
    const params = buildPageParams();
    expect(params.get('page')).toBe('0');
    expect(params.get('size')).toBe('20');
  });

  it('clamps pageSize to MAX_PAGE_SIZE (50)', () => {
    const params = buildPageParams({ pageSize: 51 });
    expect(Number(params.get('size'))).toBe(50);
  });

  it('does not clamp sizes at or below 50', () => {
    expect(Number(buildPageParams({ pageSize: 49 }).get('size'))).toBe(49);
    expect(Number(buildPageParams({ pageSize: 50 }).get('size'))).toBe(50);
  });

  it('passes page number through', () => {
    const params = buildPageParams({ page: 3, pageSize: 20 });
    expect(params.get('page')).toBe('3');
  });

  it('includes sort when provided', () => {
    const params = buildPageParams({ sort: 'createdAt,desc' });
    expect(params.get('sort')).toBe('createdAt,desc');
  });

  it('omits sort when not provided', () => {
    const params = buildPageParams({ page: 0, pageSize: 10 });
    expect(params.has('sort')).toBe(false);
  });

  it('MAX_PAGE_SIZE is 50', () => {
    expect(MAX_PAGE_SIZE).toBe(50);
  });
});

describe('buildKeysetParams', () => {
  it('clamps pageSize to MAX_PAGE_SIZE', () => {
    const params = buildKeysetParams({ pageSize: 100 });
    expect(Number(params.get('size'))).toBe(50);
  });

  it('includes cursor when provided', () => {
    const params = buildKeysetParams({ cursor: 'abc123' });
    expect(params.get('cursor')).toBe('abc123');
  });

  it('omits cursor when not provided', () => {
    const params = buildKeysetParams({ pageSize: 10 });
    expect(params.has('cursor')).toBe(false);
  });
});

describe('parsePaginatedResponse', () => {
  const fixture = {
    data: [{ id: 'a' }, { id: 'b' }],
    page: { number: 0, size: 20, totalElements: 2, totalPages: 1, estimated: false },
    _links: { self: '/api/v1/work-orders?page=0', next: null, prev: null },
  };

  it('returns data, page metadata and links', () => {
    const result = parsePaginatedResponse(fixture);
    expect(result.data).toHaveLength(2);
    expect(result.page.totalElements).toBe(2);
    expect(result.links.self).toBe('/api/v1/work-orders?page=0');
    expect(result.links.next).toBeNull();
  });

  it('uses safe defaults when page metadata is absent', () => {
    const { page, _links, ...partial } = fixture;
    const result = parsePaginatedResponse(partial);
    expect(result.page.number).toBe(0);
    expect(result.page.totalPages).toBe(1);
  });

  it('throws on non-object input', () => {
    expect(() => parsePaginatedResponse(null)).toThrow();
    expect(() => parsePaginatedResponse('string')).toThrow();
  });
});
