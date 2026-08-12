/**
 * API client for privacy administration endpoints.
 *
 * Covers: classification registry, retention policies, DSAR queue, rectification, erasure.
 * All GETs return the platform PagedResponse or single-item envelope.
 * Runtime validation is applied at the boundary — callers receive typed shapes or throw.
 *
 * @module api/privacyAdmin
 */

import { apiFetch } from './http.js';

// ---- Classification Registry -------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   module: string,
 *   entityName: string,
 *   fieldName: string | null,
 *   tier: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED',
 *   lawfulBasisNote: string | null,
 *   handlingNotes: string | null,
 *   createdAt: string,
 *   updatedAt: string,
 *   version: number,
 * }} ClassificationRow
 */

/**
 * @param {{ page?: number, size?: number, sort?: string, tier?: string }} params
 * @returns {Promise<unknown>}
 */
export function listClassifications({ page = 0, size = 20, sort, tier } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (sort) qs.set('sort', sort);
  if (tier) qs.set('tier', tier);
  return apiFetch(`/privacy/classifications?${qs}`);
}

/**
 * @param {string} id
 * @param {{ tier: string, lawfulBasisNote?: string, handlingNotes?: string, version: number }} body
 * @returns {Promise<unknown>}
 */
export function updateClassification(id, body) {
  return apiFetch(`/privacy/classifications/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

// ---- Retention Policies ------------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   dataCategory: string,
 *   entityName: string | null,
 *   periodValue: number,
 *   periodUnit: 'DAYS' | 'MONTHS' | 'YEARS',
 *   anchorField: string,
 *   disposalMethod: 'PHYSICAL_DELETE' | 'CRYPTO_ERASE',
 *   legalHold: boolean,
 *   ratified: boolean,
 *   enabled: boolean,
 *   notes: string | null,
 *   version: number,
 * }} RetentionPolicyRow
 */

/**
 * @param {{ page?: number, size?: number, sort?: string }} params
 * @returns {Promise<unknown>}
 */
export function listRetentionPolicies({ page = 0, size = 20, sort } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (sort) qs.set('sort', sort);
  return apiFetch(`/privacy/retention-policies?${qs}`);
}

/**
 * @param {string} id
 * @param {{ periodValue?: number, periodUnit?: string, legalHold?: boolean, notes?: string, version: number }} body
 * @returns {Promise<unknown>}
 */
export function updateRetentionPolicy(id, body) {
  return apiFetch(`/privacy/retention-policies/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/**
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export function dryRunRetention(id) {
  return apiFetch(`/privacy/retention-policies/${id}/dry-run`, { method: 'POST' });
}

// ---- DSAR Queue --------------------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   requestType: 'ACCESS' | 'PORTABILITY' | 'RECTIFICATION' | 'ERASURE',
 *   subjectType: string,
 *   subjectId: string,
 *   state: string,
 *   submittedAt: string,
 *   dueAt: string,
 *   remainingDays: number,
 *   atRisk: boolean,
 *   assignedHandler: string | null,
 *   outcome: string | null,
 *   version: number,
 * }} DsarRequestRow
 */

/**
 * @param {{ page?: number, size?: number, sort?: string, state?: string }} params
 * @returns {Promise<unknown>}
 */
export function listDsarRequests({ page = 0, size = 20, sort, state } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (sort)  qs.set('sort', sort);
  if (state) qs.set('state', state);
  return apiFetch(`/privacy/dsar-requests?${qs}`);
}

/**
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export function getDsarRequest(id) {
  return apiFetch(`/privacy/dsar-requests/${id}`);
}

/**
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export function getDsarExportUrl(id) {
  return apiFetch(`/privacy/dsar-requests/${id}/export`);
}

/**
 * @param {string} id
 * @param {{ event: string, verificationMethod?: string, note?: string, expectedVersion: number }} body
 * @returns {Promise<unknown>}
 */
export function transitionDsarRequest(id, body) {
  return apiFetch(`/privacy/dsar-requests/${id}/transitions`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

// ---- Erasure -----------------------------------------------------------

/**
 * @param {string} subjectType
 * @param {string} subjectId
 * @param {{ dsarRequestId: string, confirmation: string, note?: string }} body
 * @param {{ idempotencyKey: string }} opts
 * @returns {Promise<unknown>}
 */
export function initiateErasure(subjectType, subjectId, body, { idempotencyKey } = {}) {
  return apiFetch(`/privacy/subjects/${subjectType}/${subjectId}/erasure`, {
    method: 'POST',
    body: JSON.stringify(body),
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
  });
}

/**
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export function getErasure(id) {
  return apiFetch(`/privacy/erasures/${id}`);
}
