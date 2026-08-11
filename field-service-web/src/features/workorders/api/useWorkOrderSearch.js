/**
 * @fileoverview TanStack Query hook for the work order board search endpoint.
 *
 * AC-3: Uses conditional GET with 30-second refetch interval.
 * A 304 response returns undefined, which TanStack Query treats as "no change",
 * keeping the previous data in place without triggering a re-render.
 *
 * Query key includes the full normalised filter and paging state so each
 * distinct filter combination has its own stable cache entry.
 *
 * @module features/workorders/api/useWorkOrderSearch
 */
import { useQuery } from '@tanstack/react-query'
import { DASHBOARD_INTERVAL, conditionalFetch } from '../../../api/useConditionalQuery.js'
import { buildPageQuery, toQueryString } from '../../../api/pagination.js'

/**
 * @typedef {{
 *   states?: string[],
 *   priorities?: string[],
 *   technicianId?: string,
 *   customerId?: string,
 *   atRisk?: boolean,
 *   dateFrom?: string,
 *   dateTo?: string
 * }} WorkOrderFilters
 */

/**
 * @typedef {{
 *   id: string,
 *   reference: string,
 *   title: string,
 *   state: string,
 *   priority: string,
 *   customerName: string,
 *   siteName: string,
 *   assignedTechnicianId: string | null,
 *   assignedTechnicianName: string | null,
 *   responseDueAt: string | null,
 *   resolutionDueAt: string | null,
 *   atRisk: boolean,
 *   legalNextEvents: string[],
 *   version: number
 * }} WorkOrderBoardRow
 */

/**
 * Serialises filter state to a stable URL query string segment.
 * Only non-empty values are included to keep query keys minimal.
 *
 * @param {WorkOrderFilters} filters
 * @returns {string}
 */
export function serialiseFilters(filters) {
  const params = new URLSearchParams()
  if (filters.states?.length) params.set('state', filters.states.join(','))
  if (filters.priorities?.length) params.set('priority', filters.priorities.join(','))
  if (filters.technicianId) params.set('technicianId', filters.technicianId)
  if (filters.customerId) params.set('customerId', filters.customerId)
  if (filters.atRisk) params.set('atRisk', 'true')
  if (filters.dateFrom) params.set('dateFrom', filters.dateFrom)
  if (filters.dateTo) params.set('dateTo', filters.dateTo)
  return params.toString()
}

/**
 * Builds the full URL for the work order search endpoint.
 *
 * @param {{ page: number, size: number, sort?: string }} pageQuery
 * @param {WorkOrderFilters} filters
 * @returns {string}
 */
export function buildSearchUrl(pageQuery, filters) {
  const base = toQueryString(pageQuery)
  const filterStr = serialiseFilters(filters)
  const combined = [base, filterStr].filter(Boolean).join('&')
  return `/api/v1/work-orders${combined ? `?${combined}` : ''}`
}

/**
 * Fetches the work order board page with conditional GET support.
 *
 * @param {{ page: number, size: number, sort?: string }} pageQuery
 * @param {WorkOrderFilters} filters
 * @param {unknown[]} queryKey
 * @param {AbortSignal} [signal]
 * @returns {Promise<unknown>}
 */
function fetchWorkOrders(pageQuery, filters, queryKey, signal) {
  const url = buildSearchUrl(pageQuery, filters)
  return conditionalFetch(url, queryKey, signal)
}

/**
 * TanStack Query hook for the work order board.
 *
 * @param {{
 *   page?: number,
 *   size?: number,
 *   sort?: string,
 *   filters?: WorkOrderFilters,
 *   enabled?: boolean
 * }} options
 * @returns {import('@tanstack/react-query').UseQueryResult}
 */
export function useWorkOrderSearch({
  page = 0,
  size = 20,
  sort,
  filters = {},
  enabled = true,
} = {}) {
  const pageQuery = buildPageQuery({ page, size, sort })
  // Normalised query key: includes all filter state so cache entries are stable
  const queryKey = ['workOrders', 'board', pageQuery, filters]

  return useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchWorkOrders(pageQuery, filters, queryKey, signal),
    refetchInterval: DASHBOARD_INTERVAL,
    refetchIntervalInBackground: false,
    staleTime: DASHBOARD_INTERVAL - 1000, // Prevent stale re-render on 304
    enabled,
    // Retain previous data during page/filter transitions
    placeholderData: (prev) => prev,
  })
}
