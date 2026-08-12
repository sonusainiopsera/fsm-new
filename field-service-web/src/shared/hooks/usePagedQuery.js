/**
 * usePagedQuery — server-side pagination with URL state synchronisation.
 *
 * Wraps TanStack Query and synchronises page, pageSize, sort and filter
 * state with URL search params so navigation and refresh are lossless.
 *
 * Uses the platform response envelope:
 *   { data, page: { number, size, totalElements, totalPages }, _links }
 *
 * @module shared/hooks/usePagedQuery
 */

import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 50;

/**
 * @typedef {{ field: string, direction: 'asc' | 'desc' }} SortState
 *
 * @typedef {{
 *   page: number,
 *   pageSize: number,
 *   sort: SortState | null,
 *   filters: Record<string, string>,
 * }} PagedQueryState
 *
 * @typedef {{
 *   data: unknown[],
 *   page: import('../../api/pagination.js').PageMeta,
 *   links: import('../../api/pagination.js').PageLinks,
 *   isLoading: boolean,
 *   isFetching: boolean,
 *   isError: boolean,
 *   error: unknown,
 *   state: PagedQueryState,
 *   setPage: (page: number) => void,
 *   setPageSize: (size: number) => void,
 *   setSort: (sort: SortState | null) => void,
 *   setFilter: (key: string, value: string | undefined) => void,
 *   refetch: () => void,
 * }} PagedQueryResult
 */

/**
 * @param {{
 *   queryKey: unknown[],
 *   queryFn: (state: PagedQueryState, signal: AbortSignal) => Promise<unknown>,
 *   defaultPageSize?: number,
 *   allowedSorts?: Set<string>,
 *   defaultSort?: SortState | null,
 *   staleTime?: number,
 *   enabled?: boolean,
 * }} options
 * @returns {PagedQueryResult}
 */
export function usePagedQuery({
  queryKey,
  queryFn,
  defaultPageSize = DEFAULT_PAGE_SIZE,
  allowedSorts = new Set(),
  defaultSort = null,
  staleTime = 30_000,
  enabled = true,
}) {
  const [searchParams, setSearchParams] = useSearchParams();

  // Read state from URL params
  const page = Math.max(0, parseInt(searchParams.get('page') ?? '0', 10) || 0);
  const pageSize = Math.min(
    MAX_PAGE_SIZE,
    Math.max(1, parseInt(searchParams.get('size') ?? String(defaultPageSize), 10) || defaultPageSize),
  );

  const sortField = searchParams.get('sort');
  const sortDir = searchParams.get('dir');
  const sort = useMemo(() => {
    if (!sortField) return defaultSort;
    if (allowedSorts.size > 0 && !allowedSorts.has(sortField)) return defaultSort;
    return { field: sortField, direction: /** @type {'asc'|'desc'} */ (sortDir === 'desc' ? 'desc' : 'asc') };
  }, [sortField, sortDir, defaultSort, allowedSorts]);

  // Collect filter params (anything that isn't page/size/sort/dir)
  const filters = useMemo(() => {
    /** @type {Record<string, string>} */
    const f = {};
    for (const [k, v] of searchParams.entries()) {
      if (k !== 'page' && k !== 'size' && k !== 'sort' && k !== 'dir') {
        f[k] = v;
      }
    }
    return f;
  }, [searchParams]);

  const state = useMemo(
    () => ({ page, pageSize, sort, filters }),
    [page, pageSize, sort, filters],
  );

  // TanStack Query
  const fullKey = useMemo(
    () => [...queryKey, { page, pageSize, sort, filters }],
    [queryKey, page, pageSize, sort, filters],
  );

  const queryResult = useQuery({
    queryKey: fullKey,
    queryFn: ({ signal }) => queryFn(state, signal),
    staleTime,
    enabled,
    placeholderData: (prev) => prev,
  });

  const parsed = useMemo(() => {
    const envelope = queryResult.data;
    if (!envelope || typeof envelope !== 'object') {
      return { data: [], page: { number: 0, size: pageSize, totalElements: 0, totalPages: 0, estimated: false }, links: { self: null, next: null, prev: null } };
    }
    return {
      data: Array.isArray(envelope.data) ? envelope.data : [],
      page: {
        number:        envelope.page?.number        ?? 0,
        size:          envelope.page?.size          ?? pageSize,
        totalElements: envelope.page?.totalElements ?? 0,
        totalPages:    envelope.page?.totalPages    ?? 1,
        estimated:     envelope.page?.estimated     ?? false,
      },
      links: {
        self: envelope._links?.self ?? null,
        next: envelope._links?.next ?? null,
        prev: envelope._links?.prev ?? null,
      },
    };
  }, [queryResult.data, pageSize]);

  // Setters — update URL search params
  const setPage = useCallback((newPage) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      // Recover to last page if beyond bounds
      const clamped = Math.max(0, newPage);
      next.set('page', String(clamped));
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  const setPageSize = useCallback((newSize) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('size', String(Math.min(MAX_PAGE_SIZE, Math.max(1, newSize))));
      next.set('page', '0');
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  const setSort = useCallback((/** @type {SortState | null} */ newSort) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (!newSort) {
        next.delete('sort');
        next.delete('dir');
      } else {
        next.set('sort', newSort.field);
        next.set('dir', newSort.direction);
      }
      next.set('page', '0');
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  const setFilter = useCallback((key, value) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value === undefined || value === '' || value === null) {
        next.delete(key);
      } else {
        next.set(key, value);
      }
      next.set('page', '0');
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  return {
    ...parsed,
    isLoading:  queryResult.isLoading,
    isFetching: queryResult.isFetching,
    isError:    queryResult.isError,
    error:      queryResult.error,
    state,
    setPage,
    setPageSize,
    setSort,
    setFilter,
    refetch: queryResult.refetch,
  };
}
