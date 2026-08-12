/**
 * @fileoverview Portal API client — submission, site/asset listing, status polling,
 * service history browser, and CSAT survey.
 *
 * Endpoints:
 *   GET  /api/v1/portal/sites                              → site list for authenticated customer
 *   GET  /api/v1/portal/sites/:siteId/assets               → assets at a site
 *   POST /api/v1/portal/service-requests                   → submit service request
 *   GET  /api/v1/portal/service-requests                   → paginated service history
 *   GET  /api/v1/portal/service-requests/:id/status        → conditional GET status polling
 *   GET  /api/v1/portal/surveys                            → list CSAT surveys
 *   POST /api/v1/portal/surveys/:id/response               → submit survey response
 *
 * Pagination contract (AC-2):
 *   MAX_HISTORY_PAGE_SIZE = 50; sort options are allow-listed.
 *   Navigation follows links.next / links.prev from the server envelope only.
 */

import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { get, post } from './http.js'
import { useConditionalQuery, PORTAL_INTERVAL } from './useConditionalQuery.js'
import { MAX_PAGE_SIZE } from './pagination.js'

const BASE = '/api/v1/portal'

// ── Contract constants ───────────────────────────────────────────────────────

/** Maximum page size for history; mirrors the server-side clamp (AC-2). */
export const MAX_HISTORY_PAGE_SIZE = MAX_PAGE_SIZE

/**
 * Allow-listed sort fields for service history (AC-2).
 * Matches the server's accepted fields: createdAt, state, closedAt.
 */
export const HISTORY_SORT_OPTIONS = [
  { value: 'createdAt', label: 'Date opened' },
  { value: 'closedAt', label: 'Date closed' },
  { value: 'state', label: 'Status' },
]

// ── Query keys ──────────────────────────────────────────────────────────────

export const portalKeys = {
  sites: () => ['portal', 'sites'],
  siteAssets: (siteId) => ['portal', 'sites', siteId, 'assets'],
  status: (requestId) => ['portal', 'status', requestId],
  history: (filters) => ['portal', 'history', filters],
  surveys: (workOrderId) => ['portal', 'surveys', workOrderId ?? null],
}

// ── Site and asset queries ───────────────────────────────────────────────────

/**
 * Lists sites scoped to the authenticated customer's account.
 * @returns {import('@tanstack/react-query').UseQueryResult<Array<{id:string, name:string, address:string}>>}
 */
export function useSites() {
  return useQuery({
    queryKey: portalKeys.sites(),
    queryFn: ({ signal }) => get(`${BASE}/sites`, { signal }),
    staleTime: 5 * 60_000,
  })
}

/**
 * Lists assets at the given site.
 * @param {string | null | undefined} siteId
 * @returns {import('@tanstack/react-query').UseQueryResult<Array<{id:string, name:string, assetTag:string}>>}
 */
export function useSiteAssets(siteId) {
  return useQuery({
    queryKey: portalKeys.siteAssets(siteId),
    queryFn: ({ signal }) => get(`${BASE}/sites/${siteId}/assets`, { signal }),
    enabled: Boolean(siteId),
    staleTime: 5 * 60_000,
  })
}

// ── Submission mutation ──────────────────────────────────────────────────────

/**
 * @typedef {{
 *   siteId: string,
 *   assetId?: string | null,
 *   faultDescription: string,
 *   contactPreference: 'EMAIL' | 'PHONE',
 *   preferredWindow?: { fromAt: string, toAt: string } | null
 * }} SubmitServiceRequestPayload
 *
 * @typedef {{
 *   workOrderId: string,
 *   reference: string,
 *   state: string,
 *   origin: string,
 *   respondByAt: string,
 *   resolveByAt: string
 * }} SubmitServiceRequestResult
 */

/**
 * Mutation hook for submitting a portal service request.
 *
 * The Idempotency-Key is NOT generated inside this hook — callers must supply a stable
 * key that was generated once per form instance (via useRef / useMemo), so retries
 * reuse the same key rather than creating duplicate work orders.
 *
 * @param {string} idempotencyKey  Stable UUID generated once per form mount.
 * @returns {import('@tanstack/react-query').UseMutationResult<{data: SubmitServiceRequestResult}, Error, SubmitServiceRequestPayload>}
 */
export function useSubmitServiceRequest(idempotencyKey) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (/** @type {SubmitServiceRequestPayload} */ payload) => {
      const body = {
        siteId: payload.siteId,
        faultDescription: payload.faultDescription,
        contactPreference: payload.contactPreference,
      }
      if (payload.assetId) body.assetId = payload.assetId
      if (payload.preferredWindow) body.preferredWindow = payload.preferredWindow

      return post(`${BASE}/service-requests`, body, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
    },
    onSuccess: (result) => {
      const requestId = result?.data?.workOrderId
      if (requestId) {
        queryClient.invalidateQueries({ queryKey: portalKeys.status(requestId) })
      }
    },
  })
}

// ── Status conditional-GET query ────────────────────────────────────────────

/**
 * @typedef {{
 *   workOrderId: string,
 *   reference: string,
 *   statusLabel: string,
 *   statusDescription: string,
 *   respondByAt: string,
 *   resolveByAt: string,
 *   appointmentWindow: { fromAt: string, toAt: string } | null,
 *   technician: { firstName: string, roleLabel: string } | null,
 *   milestones: Array<{ at: string, label: string }>,
 *   freshness: { observedAt: string, staleAfterSeconds: number, degraded: boolean }
 * }} PortalStatusData
 */

/**
 * Polls the portal status endpoint every 60 seconds using conditional GET.
 * A 304 response retains the previously rendered data without a loading flash.
 *
 * @param {string | null | undefined} requestId  Work order UUID from the submission response.
 * @returns {import('@tanstack/react-query').UseQueryResult<{data: PortalStatusData}>}
 */
export function useServiceRequestStatus(requestId) {
  return useConditionalQuery({
    queryKey: portalKeys.status(requestId),
    url: `/api/v1/portal/service-requests/${requestId}/status`,
    refetchInterval: PORTAL_INTERVAL,
    enabled: Boolean(requestId),
  })
}

// ── Service history query ────────────────────────────────────────────────────

/**
 * @typedef {{
 *   page?: number,
 *   size?: number,
 *   sort?: string,
 *   siteId?: string | null,
 *   statusGroup?: 'OPEN' | 'CLOSED' | null,
 *   fromDate?: string | null,
 *   toDate?: string | null
 * }} HistoryFilters
 */

/**
 * Paginated service history for the authenticated customer.
 *
 * - keepPreviousData prevents flash of empty state during page transitions (AC-1).
 * - size is clamped to MAX_HISTORY_PAGE_SIZE before dispatch (AC-2).
 * - sort is validated against HISTORY_SORT_OPTIONS before dispatch (AC-2).
 *
 * @param {HistoryFilters} filters
 * @returns {import('@tanstack/react-query').UseQueryResult<import('./pagination.js').PagedResponse>}
 */
export function useServiceHistory(filters = {}) {
  const {
    page = 0,
    size = 20,
    sort = 'createdAt',
    siteId,
    statusGroup,
    fromDate,
    toDate,
  } = filters

  // AC-2: clamp size; reject sort if not allow-listed (falls back to default)
  const safeSize = Math.min(Math.max(1, Math.floor(size)), MAX_HISTORY_PAGE_SIZE)
  const allowedSorts = HISTORY_SORT_OPTIONS.map(o => o.value)
  const safeSort = allowedSorts.includes(sort) ? sort : 'createdAt'

  const params = new URLSearchParams()
  params.set('page', String(Math.max(0, Math.floor(page))))
  params.set('size', String(safeSize))
  params.set('sort', safeSort)
  if (siteId) params.set('siteId', siteId)
  if (statusGroup) params.set('statusGroup', statusGroup)
  if (fromDate) params.set('fromDate', fromDate)
  if (toDate) params.set('toDate', toDate)

  const url = `${BASE}/service-requests?${params.toString()}`

  const queryFilters = { page, size: safeSize, sort: safeSort, siteId, statusGroup, fromDate, toDate }

  return useQuery({
    queryKey: portalKeys.history(queryFilters),
    queryFn: ({ signal }) => get(url.replace('/api/v1', ''), { signal }),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  })
}

// ── Survey queries and mutations ─────────────────────────────────────────────

/**
 * Fetches the CSAT surveys for the authenticated customer,
 * optionally filtered to those matching the given workOrderId.
 *
 * @param {string | null | undefined} workOrderId
 * @returns {import('@tanstack/react-query').UseQueryResult}
 */
export function useSurveys(workOrderId) {
  const params = new URLSearchParams()
  params.set('page', '0')
  params.set('size', '10')
  if (workOrderId) params.set('workOrderId', workOrderId)

  return useQuery({
    queryKey: portalKeys.surveys(workOrderId),
    queryFn: ({ signal }) => get(`${BASE}/surveys?${params.toString()}`.replace('/api/v1', ''), { signal }),
    staleTime: 30_000,
  })
}

/**
 * Mutation to submit a CSAT survey response.
 *
 * The server is the sole authority on duplicate detection and window expiry.
 * 409 → already answered; 422 → window expired.
 *
 * @param {string} surveyId
 * @returns {import('@tanstack/react-query').UseMutationResult}
 */
export function useSubmitSurveyResponse(surveyId) {
  return useMutation({
    mutationFn: (/** @type {{ score: number, npsScore?: number | null, comment?: string | null }} */ payload) => {
      const body = { score: payload.score }
      if (payload.npsScore != null) body.npsScore = payload.npsScore
      if (payload.comment != null && payload.comment.trim() !== '') body.comment = payload.comment
      return post(`${BASE}/surveys/${surveyId}/response`.replace('/api/v1', ''), body)
    },
  })
}
