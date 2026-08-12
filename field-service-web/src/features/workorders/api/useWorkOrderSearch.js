/**
 * useWorkOrderSearch — dispatcher board query hook.
 *
 * Wraps the work-order search endpoint with:
 * - 30-second conditional ETag polling (304 = no re-render)
 * - Stable query key from normalised filter + paging state
 * - AbortSignal forwarding for request cancellation
 * - URL search-param synchronisation (URL is the source of truth)
 *
 * @module features/workorders/api/useWorkOrderSearch
 */

import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useConditionalQuery, DASHBOARD_INTERVAL } from '../../../api/useConditionalQuery.js';
import { apiFetch } from '../../../api/http.js';

const MAX_PAGE_SIZE = 50;
const DEFAULT_PAGE_SIZE = 25;

/**
 * @typedef {{
 *   id: string,
 *   reference: string,
 *   customerName: string,
 *   siteName: string,
 *   priority: string,
 *   state: string,
 *   assignedTechnicianName: string | null,
 *   responseDeadline: string | null,
 *   resolutionDeadline: string | null,
 *   atRisk: boolean,
 *   legalNextEvents: string[],
 *   version: number,
 *   createdAt: string,
 * }} WorkOrderRow
 *
 * @typedef {{
 *   states: string[],
 *   priorities: string[],
 *   technicianId: string | null,
 *   customerId: string | null,
 *   dateFrom: string | null,
 *   dateTo: string | null,
 *   atRisk: boolean,
 * }} BoardFilters
 *
 * @typedef {{ field: string, direction: 'asc' | 'desc' }} SortState
 *
 * @typedef {{
 *   page: number,
 *   pageSize: number,
 *   sort: SortState | null,
 *   filters: BoardFilters,
 * }} BoardState
 *
 * @typedef {{
 *   data: WorkOrderRow[],
 *   pageMeta: { number: number, size: number, totalElements: number, totalPages: number },
 *   isLoading: boolean,
 *   isFetching: boolean,
 *   isError: boolean,
 *   error: unknown,
 *   state: BoardState,
 *   setPage: (n: number) => void,
 *   setSort: (s: SortState | null) => void,
 *   setFilter: (key: keyof BoardFilters, value: unknown) => void,
 *   refetch: () => void,
 * }} UseWorkOrderSearchResult
 */

/**
 * @param {string} str
 * @returns {string[]}
 */
function splitComma(str) {
  if (!str) return [];
  return str.split(',').map((s) => s.trim()).filter(Boolean);
}

/**
 * Builds a normalised query string from board state for use as the query key
 * and request URL. Ensures identical states produce identical keys.
 *
 * @param {BoardState} state
 * @returns {Record<string, string>}
 */
function buildParams(state) {
  const p = {};
  p.page = String(state.page);
  p.size = String(state.pageSize);
  if (state.sort) {
    p.sort = state.sort.field;
    p.dir  = state.sort.direction;
  }
  if (state.filters.states.length > 0)  p.states     = state.filters.states.join(',');
  if (state.filters.priorities.length > 0) p.priorities = state.filters.priorities.join(',');
  if (state.filters.technicianId)  p.technicianId = state.filters.technicianId;
  if (state.filters.customerId)    p.customerId   = state.filters.customerId;
  if (state.filters.dateFrom)      p.dateFrom     = state.filters.dateFrom;
  if (state.filters.dateTo)        p.dateTo       = state.filters.dateTo;
  if (state.filters.atRisk)        p.atRisk       = 'true';
  return p;
}

/**
 * Fetches the work-order board page from the server.
 *
 * @param {BoardState} state
 * @param {AbortSignal} signal
 * @param {string | null} ifNoneMatch
 * @returns {Promise<unknown>}
 */
async function fetchWorkOrders(state, signal, ifNoneMatch) {
  const params = buildParams(state);
  const qs = new URLSearchParams(params).toString();
  const headers = ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : {};
  return apiFetch(`/work-orders?${qs}`, { signal, headers });
}

/**
 * Dispatcher work-order board hook.
 *
 * URL search params are the single source of truth for all board state so
 * the browser back/forward buttons and link-sharing work correctly.
 *
 * @returns {UseWorkOrderSearchResult}
 */
export function useWorkOrderSearch() {
  const [searchParams, setSearchParams] = useSearchParams();

  // --- Read state from URL -------------------------------------------------

  const page = Math.max(0, parseInt(searchParams.get('page') ?? '0', 10) || 0);
  const pageSize = Math.min(
    MAX_PAGE_SIZE,
    Math.max(1, parseInt(searchParams.get('size') ?? String(DEFAULT_PAGE_SIZE), 10) || DEFAULT_PAGE_SIZE),
  );

  const sortField = searchParams.get('sort');
  const sortDir   = searchParams.get('dir');
  /** @type {SortState | null} */
  const sort = sortField
    ? { field: sortField, direction: /** @type {'asc'|'desc'} */ (sortDir === 'desc' ? 'desc' : 'asc') }
    : null;

  /** @type {BoardFilters} */
  const filters = useMemo(() => ({
    states:       splitComma(searchParams.get('states') ?? ''),
    priorities:   splitComma(searchParams.get('priorities') ?? ''),
    technicianId: searchParams.get('technicianId') ?? null,
    customerId:   searchParams.get('customerId')   ?? null,
    dateFrom:     searchParams.get('dateFrom')     ?? null,
    dateTo:       searchParams.get('dateTo')       ?? null,
    atRisk:       searchParams.get('atRisk') === 'true',
  }), [searchParams]);

  /** @type {BoardState} */
  const state = useMemo(
    () => ({ page, pageSize, sort, filters }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [page, pageSize, sort?.field, sort?.direction, filters],
  );

  // --- Stable query key derived from normalised params --------------------

  const queryKey = useMemo(
    () => ['work-orders', 'board', buildParams(state)],
    [state],
  );

  // --- Conditional ETag polling query ------------------------------------

  const queryResult = useConditionalQuery({
    queryKey,
    queryFn: ({ signal, ifNoneMatch }) => fetchWorkOrders(state, signal, ifNoneMatch),
    refetchInterval: DASHBOARD_INTERVAL,
  });

  // --- Parse envelope -----------------------------------------------------

  const { data: envelope } = queryResult;
  const rows = Array.isArray(envelope?.data) ? envelope.data : [];
  const pageMeta = {
    number:        envelope?.page?.number        ?? 0,
    size:          envelope?.page?.size          ?? pageSize,
    totalElements: envelope?.page?.totalElements ?? 0,
    totalPages:    envelope?.page?.totalPages    ?? 1,
  };

  // --- URL setters --------------------------------------------------------

  const setPage = useCallback((newPage) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('page', String(Math.max(0, newPage)));
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  const setSort = useCallback((/** @type {SortState | null} */ newSort) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (!newSort) { next.delete('sort'); next.delete('dir'); }
      else { next.set('sort', newSort.field); next.set('dir', newSort.direction); }
      next.set('page', '0');
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  /**
   * @param {keyof BoardFilters} key
   * @param {unknown} value
   */
  const setFilter = useCallback((key, value) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (Array.isArray(value)) {
        if (value.length === 0) next.delete(key);
        else next.set(key, value.join(','));
      } else if (value === null || value === undefined || value === '' || value === false) {
        next.delete(key);
      } else {
        next.set(key, String(value));
      }
      next.set('page', '0');
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  return {
    data:       rows,
    pageMeta,
    isLoading:  queryResult.isLoading,
    isFetching: queryResult.isFetching,
    isError:    queryResult.isError,
    error:      queryResult.error,
    state,
    setPage,
    setSort,
    setFilter,
    refetch:    queryResult.refetch,
  };
}
