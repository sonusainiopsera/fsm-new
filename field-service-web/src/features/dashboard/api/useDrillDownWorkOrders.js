/**
 * Drill-down work order list hook.
 *
 * Fetches GET /api/v1/analytics/dashboard/drill-down with metric, window,
 * segment and pagination parameters. Requires MANAGER or ADMIN role —
 * authorization is re-evaluated server-side on every request.
 *
 * @module useDrillDownWorkOrders
 */

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../../api/http.js';

/**
 * @typedef {{
 *   widgetValue: number | null,
 *   widgetDataAsOf: string | null,
 *   resultCount: number,
 *   status: 'MATCHED' | 'DIVERGED',
 *   reason: 'READ_MODEL_STALE' | 'PROVISIONAL_COHORT' | 'SCOPE_RESTRICTED' | 'NO_WIDGET_DATA' | null,
 * }} ReconciliationDto
 *
 * @typedef {{
 *   id: string,
 *   reference: string,
 *   state: string,
 *   priority: string,
 *   siteName?: string | null,
 *   assignedTechnicianName?: string | null,
 *   createdAt: string,
 *   resolutionDeadline?: string | null,
 * }} WorkOrderSummaryRow
 *
 * @typedef {{
 *   number: number,
 *   size: number,
 *   totalElements: number,
 *   totalPages: number,
 * }} PageMeta
 *
 * @typedef {{
 *   next: string | null,
 *   prev: string | null,
 * }} PageLinks
 *
 * @typedef {{
 *   data: WorkOrderSummaryRow[],
 *   page: PageMeta,
 *   links: PageLinks,
 *   reconciliation: ReconciliationDto,
 * }} DrillDownResponse
 */

const BASE_PATH = '/api/v1/analytics/dashboard/drill-down';

/**
 * Builds the drill-down API URL from parameters.
 *
 * @param {{ metric: string, window: string, segment?: string, page: number, size: number, sort?: string }} params
 * @returns {string}
 */
export function buildDrillDownPath({ metric, window: win, segment, page = 0, size = 20, sort }) {
  const params = new URLSearchParams({ metric, window: win });
  if (segment && segment !== 'ALL') params.set('segment', segment);
  params.set('page', String(page));
  params.set('size', String(Math.min(size, 50)));
  if (sort) params.set('sort', sort);
  return `${BASE_PATH}?${params}`;
}

/**
 * Fetches the drill-down work order list for a KPI metric.
 *
 * @param {{
 *   metric: string,
 *   window: string,
 *   segment?: string,
 *   page?: number,
 *   size?: number,
 *   sort?: string,
 *   enabled?: boolean,
 * }} options
 * @returns {import('@tanstack/react-query').UseQueryResult<DrillDownResponse>}
 */
export function useDrillDownWorkOrders({
  metric,
  window: win,
  segment = 'ALL',
  page = 0,
  size = 20,
  sort = 'createdAt:desc',
  enabled = true,
} = {}) {
  const queryKey = /** @type {const} */ (['drill-down', metric, win, segment, page, size, sort]);

  return useQuery({
    queryKey,
    queryFn: ({ signal }) => {
      const path = buildDrillDownPath({ metric, window: win, segment, page, size, sort });
      return apiFetch(path, { signal });
    },
    enabled: enabled && Boolean(metric) && Boolean(win),
    staleTime: 30_000,
    retry: 1,
  });
}
