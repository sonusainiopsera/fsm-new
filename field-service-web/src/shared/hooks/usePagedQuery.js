/**
 * @fileoverview TanStack Query 5 wrapper for server-side paginated endpoints.
 *
 * Wraps useQuery with:
 * - Standardised query key including page/size/sort/filter
 * - Response envelope parsing (data[], page metadata, links)
 * - keepPreviousData behaviour during page transitions (placeholderData)
 * - Stable return shape: { rows, page, links, isLoading, isError, error, refetch }
 *
 * @module shared/hooks/usePagedQuery
 */
import { useQuery } from '@tanstack/react-query'
import { parsePagedEnvelope } from '../../api/pagination.js'

/**
 * @template T
 * @typedef {{
 *   rows: T[],
 *   page: import('../../api/pagination.js').PageMeta,
 *   links: import('../../api/pagination.js').PageLinks,
 *   isLoading: boolean,
 *   isFetching: boolean,
 *   isError: boolean,
 *   error: import('../../api/errors.js').ClientError | null,
 *   refetch: () => void
 * }} PagedQueryResult
 */

/**
 * @template T
 * @param {{
 *   queryKey: unknown[],
 *   queryFn: (ctx: { signal: AbortSignal }) => Promise<unknown>,
 *   enabled?: boolean
 * }} options
 * @returns {PagedQueryResult<T>}
 */
export function usePagedQuery({ queryKey, queryFn, enabled = true }) {
  const result = useQuery({
    queryKey,
    queryFn,
    enabled,
    placeholderData: (prev) => prev,
  })

  const envelope = parsePagedEnvelope(result.data)

  return {
    rows: /** @type {T[]} */ (envelope.data),
    page: envelope.page,
    links: envelope.links,
    isLoading: result.isLoading,
    isFetching: result.isFetching,
    isError: result.isError,
    error: result.error ?? null,
    refetch: result.refetch,
  }
}
