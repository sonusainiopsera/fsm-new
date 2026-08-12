/**
 * Portal API client — typed wrappers for the customer portal endpoints.
 *
 * Endpoints:
 *   GET  /api/v1/portal/sites                         — sites scoped to caller's account
 *   GET  /api/v1/portal/sites/:siteId/assets          — assets at a site
 *   POST /api/v1/portal/service-requests              — submit a service request
 *   GET  /api/v1/portal/service-requests/:id/status   — conditional-GET status
 *
 * ETag / 304: apiFetch returns { _304: true, etag } on 304 — callers preserve
 * cached data and skip re-renders (forwarded to TanStack Query via usePortalQuery).
 *
 * Idempotency-Key: callers (mutations) must generate one UUID per form instance
 * and pass it to apiFetch via the Idempotency-Key header. The http layer auto-
 * generates a key only when the caller has not already set one, so this client
 * generates a stable key per-call to enable retry safety.
 */

import { apiFetch } from './http.js';

// ─── Sites ────────────────────────────────────────────────────────────────────

/**
 * @typedef {{ id: string, name: string, address: string, active: boolean }} PortalSite
 */

/**
 * Returns the list of sites the authenticated customer can raise requests against.
 * @param {{ signal?: AbortSignal, ifNoneMatch?: string | null }} [opts]
 * @returns {Promise<{ data: PortalSite[] } | { _304: true }>}
 */
export async function fetchPortalSites({ signal, ifNoneMatch } = {}) {
  return apiFetch('/portal/sites', {
    signal,
    headers: ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : undefined,
  });
}

// ─── Assets ───────────────────────────────────────────────────────────────────

/**
 * @typedef {{ id: string, assetTag: string | null, model: string | null, assetType: string }} PortalAsset
 */

/**
 * Returns assets at a site filtered to those the customer can reference.
 * Returns an empty array when the site has no registered assets.
 * @param {string} siteId
 * @param {{ signal?: AbortSignal }} [opts]
 * @returns {Promise<{ data: PortalAsset[] }>}
 */
export async function fetchSiteAssets(siteId, { signal } = {}) {
  return apiFetch(`/portal/sites/${encodeURIComponent(siteId)}/assets`, { signal });
}

// ─── Submit ───────────────────────────────────────────────────────────────────

/**
 * @typedef {{
 *   siteId: string,
 *   assetId: string | null,
 *   faultDescription: string,
 *   contactPreference: 'EMAIL' | 'PHONE',
 *   preferredWindow?: { fromAt: string, toAt: string } | null,
 * }} CreateServiceRequestBody
 *
 * @typedef {{
 *   workOrderId: string,
 *   reference: string,
 *   state: string,
 *   origin: string,
 *   respondByAt: string | null,
 *   resolveByAt: string | null,
 * }} ServiceRequestResponse
 */

/**
 * Submits a new portal service request.
 *
 * Idempotency-Key must be generated once per form instance and kept stable
 * across retries so a network hiccup cannot create a duplicate request.
 *
 * @param {CreateServiceRequestBody} body
 * @param {string} idempotencyKey  UUID generated once per form instance
 * @param {{ signal?: AbortSignal }} [opts]
 * @returns {Promise<ServiceRequestResponse>}
 */
export async function submitServiceRequest(body, idempotencyKey, { signal } = {}) {
  return apiFetch('/portal/service-requests', {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
    headers: {
      'Idempotency-Key': idempotencyKey,
      'Content-Type': 'application/json',
    },
  });
}

// ─── Status ───────────────────────────────────────────────────────────────────

/**
 * @typedef {{
 *   workOrderId: string,
 *   reference: string,
 *   statusLabel: string,
 *   statusDescription: string,
 *   respondByAt: string | null,
 *   resolveByAt: string | null,
 *   appointmentWindow?: { fromAt: string, toAt: string } | null,
 *   technician?: { firstName: string, roleLabel: string } | null,
 *   milestones: Array<{ at: string, label: string }>,
 *   freshness: { observedAt: string, staleAfterSeconds: number, degraded: boolean },
 * }} PortalStatusView
 */

/**
 * Fetches the current status of a portal service request.
 * Forwards If-None-Match for conditional GET so a 304 costs ~2 KB.
 *
 * @param {string} requestId
 * @param {{ signal?: AbortSignal, ifNoneMatch?: string | null }} [opts]
 * @returns {Promise<PortalStatusView | { _304: true, etag?: string }>}
 */
export async function fetchServiceRequestStatus(requestId, { signal, ifNoneMatch } = {}) {
  const result = await apiFetch(
    `/portal/service-requests/${encodeURIComponent(requestId)}/status`,
    {
      signal,
      headers: ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : undefined,
    },
  );
  return result;
}
