/**
 * @fileoverview TanStack Query hook for batched KPI widget polling (WO-167).
 *
 * Batches all 11 metrics into a single conditional GET against:
 *   GET /api/v1/analytics/dashboard/widgets?metrics=...&window=...&segment=...
 *
 * Uses ETag/304 polling via useConditionalQuery so unchanged responses keep
 * the existing data reference and do not trigger widget re-renders.
 */

import { useConditionalQuery, DASHBOARD_INTERVAL } from '../../../api/useConditionalQuery.js'

const BASE_URL = '/api/v1/analytics/dashboard/widgets'

/** All metric keys defined in the server MetricKey enum (WO-166). */
export const ALL_METRIC_KEYS = [
  'SLA_COMPLIANCE_RATE',
  'SLA_RESOLUTION_MEAN',
  'SLA_RESOLUTION_MEDIAN',
  'SLA_BREACH_COUNT',
  'FTF_RATE',
  'REPEAT_VISIT_COUNT',
  'BACKLOG_OPEN_COUNT',
  'BACKLOG_ON_HOLD_COUNT',
  'WORKLOAD_BALANCE_CV',
  'UTILIZATION_RATE',
  'JOBS_PER_DAY',
]

/** @typedef {'SEVEN_DAYS' | 'THIRTY_DAYS' | 'NINETY_DAYS'} WindowKey */
/** @typedef {'ALL' | 'PRIORITY' | 'TEAM'} SegmentKey */

/**
 * @typedef {{
 *   metricKey: string,
 *   segment: string,
 *   window: string,
 *   value: number | null,
 *   unit: string,
 *   numerator: number | null,
 *   denominator: number | null,
 *   sampleCount: number,
 *   trend: Array<{ts: number, value: number}>,
 *   deltaVsPriorPeriod: number | null,
 *   targetAttainment: number | 'BASELINE_PENDING' | null,
 *   maturity: 'MATURED' | 'PROVISIONAL',
 *   dataAsOf: string,
 *   stalenessSeconds: number,
 *   degraded: boolean,
 *   degradedReason: string | null
 * }} WidgetDto
 */

/**
 * @typedef {{
 *   data: WidgetDto[],
 *   page: { number: number, size: number, totalElements: number, totalPages: number }
 * }} WidgetsResponse
 */

/**
 * Builds the request URL for the widget endpoint.
 *
 * @param {WindowKey} window
 * @param {SegmentKey} segment
 * @returns {string}
 */
export function buildWidgetsUrl(window, segment) {
  const params = new URLSearchParams()
  for (const key of ALL_METRIC_KEYS) params.append('metrics', key)
  params.set('window', window)
  if (segment && segment !== 'ALL') params.set('segment', segment)
  return `${BASE_URL}?${params.toString()}`
}

/**
 * Fetches all KPI widgets for the given window and segment using conditional
 * 30-second polling.  Returns `undefined` on 304 so TQ keeps the previous data
 * reference unchanged (no re-render storm).
 *
 * @param {{
 *   window?: WindowKey,
 *   segment?: SegmentKey,
 *   enabled?: boolean
 * }} [opts]
 * @returns {import('@tanstack/react-query').UseQueryResult<WidgetsResponse>}
 */
export function useDashboardWidgets({ window = 'THIRTY_DAYS', segment = 'ALL', enabled = true } = {}) {
  const queryKey = ['dashboard', 'widgets', window, segment]
  const url = buildWidgetsUrl(window, segment)

  return useConditionalQuery({
    queryKey,
    url,
    refetchInterval: DASHBOARD_INTERVAL,
    enabled,
  })
}
