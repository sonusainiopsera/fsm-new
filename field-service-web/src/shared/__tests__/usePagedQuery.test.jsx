/**
 * Tests for usePagedQuery — URL state round-tripping and pagination controls.
 */

import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { MemoryRouter, useSearchParams } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { usePagedQuery } from '../hooks/usePagedQuery.js';

// Helper: renders the hook inside a QueryClientProvider + MemoryRouter
function HookHarness({ initialEntries = ['/'], onResult }) {
  function Inner() {
    const result = usePagedQuery({
      queryKey: ['test-items'],
      queryFn: (state) => Promise.resolve({
        data: [{ id: 'a' }, { id: 'b' }],
        page: { number: state.page, size: state.pageSize, totalElements: 25, totalPages: 2, estimated: false },
        _links: {},
      }),
      defaultPageSize: 20,
    });
    onResult(result);
    return (
      <div>
        <span data-testid="page">{result.state.page}</span>
        <span data-testid="size">{result.state.pageSize}</span>
        <span data-testid="sort">{result.state.sort?.field ?? 'none'}</span>
      </div>
    );
  }

  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={initialEntries}>
        <Inner />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('usePagedQuery', () => {
  it('reads default page 0 when URL has no params', async () => {
    let result;
    render(<HookHarness onResult={(r) => { result = r; }} />);
    await act(async () => {});
    expect(result.state.page).toBe(0);
    expect(result.state.pageSize).toBe(20);
    expect(result.state.sort).toBeNull();
  });

  it('reads page and size from URL params', async () => {
    let result;
    render(<HookHarness initialEntries={['/?page=2&size=10']} onResult={(r) => { result = r; }} />);
    await act(async () => {});
    expect(result.state.page).toBe(2);
    expect(result.state.pageSize).toBe(10);
  });

  it('clamps page size to MAX_PAGE_SIZE=50', async () => {
    let result;
    render(<HookHarness initialEntries={['/?size=999']} onResult={(r) => { result = r; }} />);
    await act(async () => {});
    expect(result.state.pageSize).toBeLessThanOrEqual(50);
  });

  it('reads sort from URL params', async () => {
    let result;
    render(
      <HookHarness
        initialEntries={['/?sort=name&dir=desc']}
        onResult={(r) => { result = r; }}
      />
    );
    await act(async () => {});
    expect(result.state.sort?.field).toBe('name');
    expect(result.state.sort?.direction).toBe('desc');
  });

  it('defaults unknown direction to asc', async () => {
    let result;
    render(
      <HookHarness
        initialEntries={['/?sort=name&dir=invalid']}
        onResult={(r) => { result = r; }}
      />
    );
    await act(async () => {});
    expect(result.state.sort?.direction).toBe('asc');
  });

  it('reads filters from extra URL params', async () => {
    let result;
    render(<HookHarness initialEntries={['/?search=acme&page=0']} onResult={(r) => { result = r; }} />);
    await act(async () => {});
    expect(result.state.filters.search).toBe('acme');
  });

  it('clamps negative page to 0', async () => {
    let result;
    render(<HookHarness initialEntries={['/?page=-5']} onResult={(r) => { result = r; }} />);
    await act(async () => {});
    expect(result.state.page).toBe(0);
  });
});
