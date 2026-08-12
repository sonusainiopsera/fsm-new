/**
 * @fileoverview Privacy API client — thin wrappers over the shared http module.
 *
 * Covers all privacy endpoints from WO-092 to WO-095 and WO-191:
 *   GET  /api/v1/privacy/classifications
 *   PUT  /api/v1/privacy/classifications/{id}
 *   GET  /api/v1/privacy/retention-policies
 *   PUT  /api/v1/privacy/retention-policies/{id}
 *   POST /api/v1/privacy/retention-policies/{id}/dry-run
 *   GET  /api/v1/privacy/dsar-requests
 *   GET  /api/v1/privacy/dsar-requests/{id}
 *   POST /api/v1/privacy/dsar-requests/{id}/transitions
 *   GET  /api/v1/privacy/dsar-requests/{id}/export
 *   POST /api/v1/privacy/subjects/{subjectType}/{subjectId}/rectifications
 *   POST /api/v1/privacy/subjects/{subjectType}/{subjectId}/erasure
 *   GET  /api/v1/privacy/erasures/{id}
 *
 * @module features/privacy/api/privacyClient
 */
import { get, post, put } from '../../../api/http.js'

const BASE = '/privacy'

// ── Classification Registry ───────────────────────────────────────────────────

/**
 * @typedef {{ id: string, module: string, entityName: string, fieldName: string, tier: string, handlingNotes: string | null, version: number }} ClassificationRow
 */

/**
 * List classifications with server-side pagination.
 * @param {{ page?: number, size?: number, sort?: string, signal?: AbortSignal }} [params]
 * @returns {Promise<import('../../../api/pagination.js').PagedResponse<ClassificationRow>>}
 */
export function listClassifications({ page = 0, size = 50, sort, signal } = {}) {
  const q = new URLSearchParams()
  q.set('page', String(page))
  q.set('size', String(Math.min(size, 50)))
  if (sort) q.set('sort', sort)
  return get(`${BASE}/classifications?${q}`, { signal })
}

/**
 * Update a classification's tier and handling notes (versioned).
 * @param {string} id
 * @param {{ tier: string, handlingNotes: string | null, version: number }} body
 * @returns {Promise<ClassificationRow>}
 */
export function updateClassification(id, body) {
  return put(`${BASE}/classifications/${id}`, body)
}

// ── Retention Policies ────────────────────────────────────────────────────────

/**
 * @typedef {{ id: string, categoryName: string, retentionPeriod: number, periodUnit: string, anchorField: string | null, disposalMethod: string, legalHold: boolean, ratified: boolean, enabled: boolean, version: number }} RetentionPolicyRow
 */

/**
 * List retention policies.
 * @param {{ page?: number, size?: number, signal?: AbortSignal }} [params]
 * @returns {Promise<import('../../../api/pagination.js').PagedResponse<RetentionPolicyRow>>}
 */
export function listRetentionPolicies({ page = 0, size = 50, signal } = {}) {
  const q = new URLSearchParams()
  q.set('page', String(page))
  q.set('size', String(Math.min(size, 50)))
  return get(`${BASE}/retention-policies?${q}`, { signal })
}

/**
 * Update a retention policy period, unit, and legal-hold flag.
 * @param {string} id
 * @param {{ retentionPeriod: number, periodUnit: string, version: number }} body
 * @returns {Promise<RetentionPolicyRow>}
 */
export function updateRetentionPolicy(id, body) {
  return put(`${BASE}/retention-policies/${id}`, body)
}

/**
 * @typedef {{ eligibleCount: number, oldestEligibleAt: string | null, cutoffAt: string, disposalMethod: string, policyId: string }} DryRunReport
 */

/**
 * Run a dry-run preview for a retention policy.
 * No data is changed.
 * @param {string} id
 * @returns {Promise<DryRunReport>}
 */
export function runRetentionDryRun(id) {
  return post(`${BASE}/retention-policies/${id}/dry-run`, {})
}

// ── DSAR Queue ────────────────────────────────────────────────────────────────

/**
 * @typedef {{ id: string, requestType: string, subjectType: string, subjectId: string, state: string, submittedAt: string, dueAt: string, atRisk: boolean }} DsarListRow
 */

/**
 * List DSAR requests with filtering and pagination.
 * @param {{ page?: number, size?: number, sort?: string, state?: string, signal?: AbortSignal }} [params]
 * @returns {Promise<import('../../../api/pagination.js').PagedResponse<DsarListRow>>}
 */
export function listDsarRequests({ page = 0, size = 50, sort, state, signal } = {}) {
  const q = new URLSearchParams()
  q.set('page', String(page))
  q.set('size', String(Math.min(size, 50)))
  if (sort) q.set('sort', sort)
  if (state) q.set('state', state)
  return get(`${BASE}/dsar-requests?${q}`, { signal })
}

/**
 * @typedef {{ id: string, requestType: string, subjectType: string, subjectId: string, state: string, submittedAt: string, dueAt: string, atRisk: boolean, stateHistory: { state: string, at: string, actor: string }[], identityVerification: { verifiedAt: string | null, method: string | null } | null, exportManifest: { generatedAt: string, sections: { name: string, rowCount: number }[] } | null }} DsarDetail
 */

/**
 * Fetch DSAR request detail.
 * @param {string} id
 * @param {{ signal?: AbortSignal }} [options]
 * @returns {Promise<DsarDetail>}
 */
export function getDsarRequest(id, { signal } = {}) {
  return get(`${BASE}/dsar-requests/${id}`, { signal })
}

/**
 * Transition a DSAR request to a new state.
 * @param {string} id
 * @param {{ event: string, note?: string }} body
 * @returns {Promise<DsarDetail>}
 */
export function transitionDsarRequest(id, body) {
  return post(`${BASE}/dsar-requests/${id}/transitions`, body)
}

/**
 * @typedef {{ downloadUrl: string, expiresAt: string }} ExportDownloadUrl
 */

/**
 * Request a fresh short-lived export download URL.
 * Never cached — always fetched fresh per click.
 * @param {string} id
 * @returns {Promise<ExportDownloadUrl>}
 */
export function getDsarExportUrl(id) {
  return get(`${BASE}/dsar-requests/${id}/export`)
}

// ── Subject Rights ────────────────────────────────────────────────────────────

/**
 * @typedef {{ applied: { entityName: string, fieldName: string, revisionId: number }[], skipped: { entityName: string, fieldName: string, reason: string }[] }} RectifyResponse
 */

/**
 * Apply field-level corrections to a subject's personal data.
 * @param {string} subjectType
 * @param {string} subjectId
 * @param {{ dsarRequestId: string, corrections: { entityName: string, fieldName: string, newValue: string }[], note?: string }} body
 * @returns {Promise<RectifyResponse>}
 */
export function rectifySubject(subjectType, subjectId, body) {
  return post(`${BASE}/subjects/${subjectType}/${subjectId}/rectifications`, body)
}

/**
 * @typedef {{ id: string, subjectType: string, subjectId: string, erasedAt: string, actor: string, sections: { name: string, rowCount: number }[], verifications: { scopeId: string, plaintextFound: boolean, checkedAt: string }[], outcome: string, refusalReason: string | null }} ErasureView
 */

/**
 * Initiate cryptographic erasure for a subject.
 * Returns 202 Accepted — erasure may complete asynchronously.
 * @param {string} subjectType
 * @param {string} subjectId
 * @param {{ dsarRequestId: string, confirmation: string, note?: string }} body
 * @returns {Promise<ErasureView>}
 */
export function initiateErasure(subjectType, subjectId, body) {
  return post(`${BASE}/subjects/${subjectType}/${subjectId}/erasure`, body)
}

/**
 * Fetch an erasure tombstone by ID.
 * @param {string} erasureId
 * @param {{ signal?: AbortSignal }} [options]
 * @returns {Promise<ErasureView>}
 */
export function getErasure(erasureId, { signal } = {}) {
  return get(`${BASE}/erasures/${erasureId}`, { signal })
}
