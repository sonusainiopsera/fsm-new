/**
 * Dashboard KPI widgets data hook.
 *
 * Polls GET /api/v1/operations/kpi-widgets every 30 seconds using conditional
 * ETag requests so unchanged data costs a 304 with no re-render.
 *
 * @module useDashboardWidgets
 */

import { useDashboardQuery } from '../../../api/useConditionalQuery.js';
import { apiFetch } from '../../../api/http.js';

/**
 * @typedef {'SETTLED' | 'PROVISIONAL' | 'NOT_MEANINGFUL' | 'BASELINE_PENDING'} WidgetMaturity
 *
 * @typedef {{
 *   id: string,
 *   label: string,
 *   value: string | number,
 *   rawValue?: number | null,
 *   delta?: number | null,
 *   deltaLabel?: string | null,
 *   target?: number | null,
 *   current?: number | null,
 *   maturity: WidgetMaturity,
 *   sparkline?: number[] | null,
 *   degraded: boolean,
 *   dataAge: string | null,
 *   unit?: string | null,
 *   notMeaningfulReason?: string | null,
 *   metricKey?: string | null,
 *   window?: string | null,
 * }} WidgetDto
 *
 * @typedef {{
 *   key: string,
 *   name: string,
 *   accent?: boolean,
 * }} SeriesDto
 *
 * @typedef {{
 *   caption: string,
 *   series: SeriesDto[],
 *   data: Record<string, number | string>[],
 * }} TrendDto
 *
 * @typedef {{
 *   meta: {
 *     window: string,
 *     generatedAt: string,
 *     degraded: boolean,
 *     allDegraded: boolean,
 *   },
 *   widgets: WidgetDto[],
 *   trend: TrendDto | null,
 * }} DashboardPayload
 */

const BASE_PATH = '/api/v1/operations/kpi-widgets';

/**
 * Builds the URL with query parameters for the KPI widgets endpoint.
 *
 * @param {{ window: string, priority?: string, team?: string }} params
 * @returns {string}
 */
function buildPath({ window, priority, team }) {
  const params = new URLSearchParams({ window });
  if (priority) params.set('priority', priority);
  if (team) params.set('team', team);
  return `${BASE_PATH}?${params}`;
}

/**
 * Fetches dashboard KPI widgets with conditional GET (ETag / If-None-Match).
 *
 * @param {{
 *   window?: '7d' | '30d' | '90d',
 *   priority?: string,
 *   team?: string,
 *   enabled?: boolean,
 * }} options
 * @returns {import('@tanstack/react-query').UseQueryResult<DashboardPayload>}
 */
export function useDashboardWidgets({
  window: windowParam = '30d',
  priority = '',
  team = '',
  enabled = true,
} = {}) {
  const queryKey = /** @type {const} */ (['dashboard-widgets', windowParam, priority, team]);

  return useDashboardQuery({
    queryKey,
    queryFn: ({ signal, ifNoneMatch }) => {
      const path = buildPath({ window: windowParam, priority, team });
      const headers = ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : {};
      return apiFetch(path, { signal, headers });
    },
    enabled,
  });
}
