/**
 * @fileoverview Portal API client — submission, site/asset listing, status polling.
 *
 * Endpoints:
 *   GET  /api/v1/portal/sites                          → site list for authenticated customer
 *   GET  /api/v1/portal/sites/:siteId/assets           → assets at a site
 *   POST /api/v1/portal/service-requests               → submit service request
 *   GET  /api/v1/portal/service-requests/:id/status    → conditional GET status polling
 *
 * Conditional GET behaviour (status endpoint):
 *   useServiceRequestStatus forwards ETag/If-None-Match via useConditionalQuery.
 *   A 304 response keeps the existing cached data without re-rendering or showing a loading skeleton.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { get, post } from './http.js'
import { useConditionalQuery, PORTAL_INTERVAL } from './useConditionalQuery.js'

const BASE = '/api/v1/portal'

// ── Query keys ──────────────────────────────────────────────────────────────

export const portalKeys = {
  sites: () => ['portal', 'sites'],
  siteAssets: (siteId) => ['portal', 'sites', siteId, 'assets'],
  status: (requestId) => ['portal', 'status', requestId],
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
