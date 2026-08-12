/**
 * Data hook for the dispatch recommendations endpoint.
 *
 * Implements keyset cursor pagination with local accumulation:
 * - Initial load fetches page 1 (cursor=null)
 * - loadMore() advances the cursor and appends the next page
 * - refresh() resets accumulation and re-fetches from page 1
 * - workOrderId change resets everything automatically
 *
 * No polling — staleTime is 10 minutes so focus-switching does not trigger
 * unnecessary re-fetches on the dispatcher's primary working surface.
 */

import { useState, useEffect, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../../api/http.js';
import { buildKeysetParams } from '../../../api/pagination.js';

/**
 * Extracts the cursor value from a links.next URL string.
 * @param {string | null | undefined} nextLink
 * @returns {string | null}
 */
function extractCursor(nextLink) {
  if (!nextLink) return null;
  try {
    const base = typeof window !== 'undefined' ? window.location.href : 'http://localhost';
    const url = new URL(nextLink, base);
    return url.searchParams.get('cursor');
  } catch {
    return null;
  }
}

/**
 * @param {string | null | undefined} workOrderId
 * @param {{ pageSize?: number }} [options]
 * @returns {{
 *   candidates: unknown[],
 *   meta: unknown,
 *   hasNext: boolean,
 *   isLoading: boolean,
 *   isFetching: boolean,
 *   isError: boolean,
 *   error: unknown,
 *   loadMore: () => void,
 *   refresh: () => void,
 *   queryKey: readonly unknown[],
 * }}
 */
export function useRecommendations(workOrderId, { pageSize = 20 } = {}) {
  const qc = useQueryClient();
  const [cursor, setCursor] = useState(null);
  const [candidates, setCandidates] = useState([]);

  const queryKey = ['recommendations', workOrderId, cursor, pageSize];

  const query = useQuery({
    queryKey,
    queryFn: async ({ signal }) => {
      const params = buildKeysetParams({ pageSize, cursor });
      return apiFetch(`/work-orders/${workOrderId}/recommendations?${params}`, { signal });
    },
    enabled: Boolean(workOrderId),
    staleTime: 10 * 60 * 1000,
    refetchOnWindowFocus: false,
    retry: (failCount, err) => (err?.status ?? 0) >= 500 && failCount < 2,
  });

  // Accumulate pages. cursor in closure is intentionally the value at the time
  // query.data settles — by then cursor state has already updated.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!query.data?.data) return;
    if (cursor === null) {
      // First page or after refresh — replace accumulated list
      setCandidates(query.data.data);
    } else {
      // Load-more page — append, dedup by technicianId
      setCandidates(prev => {
        const seen = new Set(prev.map(c => c.technicianId));
        const fresh = query.data.data.filter(c => !seen.has(c.technicianId));
        return [...prev, ...fresh];
      });
    }
  }, [query.data]); // cursor omitted intentionally — see note above

  // Reset accumulation and cursor when work order changes
  useEffect(() => {
    setCursor(null);
    setCandidates([]);
  }, [workOrderId]);

  const loadMore = useCallback(() => {
    const next = extractCursor(query.data?.links?.next);
    if (next) setCursor(next);
  }, [query.data]);

  const refresh = useCallback(() => {
    setCandidates([]);
    setCursor(null);
    qc.invalidateQueries({ queryKey: ['recommendations', workOrderId] });
  }, [qc, workOrderId]);

  return {
    candidates,
    meta: query.data?.meta ?? null,
    hasNext: query.data?.page?.hasNext ?? false,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    isError: query.isError,
    error: query.error,
    loadMore,
    refresh,
    queryKey,
  };
}
