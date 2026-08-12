/**
 * @fileoverview TanStack Query hook for KPI drill-down work order list (WO-168).
 *
 * Fetches a paginated, row-scoped work order list from:
 *   GET /api/v1/analytics/dashboard/drill-down?metric=...&window=...&segment=...&page=...&size=...
 *
 * Does NOT use conditional polling — drill-down is an on-demand request, not a background poll.
 */

import { useQuery } from '@tanstack/react-query'
import { getToken } from '../../../api/tokenStore.js'

const BASE_URL = '/api/v1/analytics/dashboard/drill-down'

/**
 * @typedef {{
 *   metric: string,
 *   window: string,
 *   segment?: string,
 *   page?: number,
 *   size?: number
 * }} DrillDownParams
 */

/**
 * @typedef {{
 *   id: string,
 *   title: string,
 *   state: string,
 *   priority: string,
 *   customerId: string,
 *   siteId: string,
 *   assignedTechnicianId: string | null,
 *   resolutionDeadline: string | null,
 *   atRisk: boolean,
 *   createdAt: string,
 *   updatedAt: string
 * }} WorkOrderRow
 */

/**
 * @typedef {{
 *   widgetValue: number | null,
 *   widgetDataAsOf: string | null,
 *   resultCount: number,
 *   status: 'MATCHED' | 'DIVERGED',
 *   reason: 'READ_MODEL_STALE' | 'PROVISIONAL_COHORT' | 'SCOPE_RESTRICTED' | null
 * }} Reconciliation
 */

/**
 * @typedef {{
 *   data: WorkOrderRow[],
 *   page: { number: number, size: number, totalElements: number, totalPages: number },
 *   links: { next: string | null, prev: string | null },
 *   reconciliation: Reconciliation
 * }} DrillDownResponse
 */

/**
 * Builds the URL for the drill-down endpoint.
 *
 * @param {DrillDownParams} params
 * @returns {string}
 */
export function buildDrillDownUrl({ metric, window, segment, page = 0, size = 20 }) {
  const params = new URLSearchParams()
  params.set('metric', metric)
  params.set('window', window)
  if (segment && segment !== 'ALL') params.set('segment', segment)
  params.set('page', String(page))
  // Server clamps to max 50 — client must not exceed it
  params.set('size', String(Math.min(50, Math.max(1, size))))
  return `${BASE_URL}?${params.toString()}`
}

/**
 * Fetches the drill-down work order list for a given metric + window + segment.
 *
 * @param {DrillDownParams & { enabled?: boolean }} opts
 * @returns {import('@tanstack/react-query').UseQueryResult<DrillDownResponse>}
 */
export function useDrillDownWorkOrders({
  metric,
  window,
  segment,
  page = 0,
  size = 20,
  enabled = true,
} = {}) {
  const queryKey = ['dashboard', 'drilldown', metric, window, segment ?? 'ALL', page, size]
  const url = buildDrillDownUrl({ metric, window, segment, page, size })

  return useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchDrillDown(url, signal),
    enabled: enabled && Boolean(metric) && Boolean(window),
    // No background refetch — drill-down is explicit; user refreshes if they want fresh data
    staleTime: 30_000,
    retry: 1,
  })
}

/**
 * @param {string} url
 * @param {AbortSignal} [signal]
 * @returns {Promise<DrillDownResponse>}
 */
async function fetchDrillDown(url, signal) {
  const token = getToken()
  const headers = new Headers({ Accept: 'application/json' })
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(url, { headers, signal })

  if (!response.ok) {
    const { normaliseError } = await import('../../../api/errors.js')
    let body = null
    try { body = await response.json() } catch { /* ignore */ }
    throw normaliseError(response.status, body)
  }

  return response.json()
}
