/**
 * API client for workforce admin endpoints.
 *
 * Covers: Technicians, Skills, Availability, Certification Types,
 * Technician Certifications (WO-119 batch upsert), and bulk eligibility query.
 *
 * IMPORTANT — certification currency:
 * The `current` and `daysUntilExpiry` fields in certification responses are
 * ALWAYS derived server-side from `expires_on` at query time. The client
 * MUST render them as-is and MUST NOT recompute or cache them.
 *
 * @module api/workforce
 */

import { apiFetch } from './http.js';

// ---- Technicians ---------------------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   userId: string,
 *   displayName: string,
 *   email: string,
 *   active: boolean,
 *   createdAt: string,
 * }} Technician
 */

/**
 * @param {{ page?: number, size?: number, sort?: string, search?: string }} params
 * @returns {Promise<unknown>}
 */
export function listTechnicians({ page = 0, size = 20, sort, search } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (sort)   qs.set('sort', sort);
  if (search) qs.set('search', search);
  return apiFetch(`/technicians?${qs}`);
}

/**
 * @param {{ userId: string }} body
 * @param {{ idempotencyKey?: string }} [opts]
 * @returns {Promise<unknown>}
 */
export function createTechnician(body, { idempotencyKey } = {}) {
  return apiFetch('/technicians', {
    method: 'POST',
    body: JSON.stringify(body),
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
  });
}

/**
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export function deactivateTechnician(id) {
  return apiFetch(`/technicians/${id}`, { method: 'DELETE' });
}

// ---- Skills (per-technician) --------------------------------------------

/**
 * @typedef {{
 *   technicianId: string,
 *   skillCode: string,
 *   skillName: string,
 *   proficiencyLevel: number,
 * }} TechnicianSkill
 */

/**
 * @param {string} technicianId
 * @param {{ page?: number, size?: number }} params
 * @returns {Promise<unknown>}
 */
export function listTechnicianSkills(technicianId, { page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  return apiFetch(`/technicians/${technicianId}/skills?${qs}`);
}

/**
 * Batch upsert skills for a technician (validate-all-then-persist).
 *
 * @param {string} technicianId
 * @param {Array<{ skillCode: string, proficiencyLevel: number }>} items
 * @param {{ idempotencyKey: string }} opts  Caller MUST supply a stable key for retry safety
 * @returns {Promise<unknown>}
 */
export function upsertTechnicianSkills(technicianId, items, { idempotencyKey }) {
  return apiFetch(`/technicians/${technicianId}/skills`, {
    method: 'PUT',
    body: JSON.stringify({ items }),
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

// ---- Availability --------------------------------------------------------

/**
 * @param {string} technicianId
 * @returns {Promise<unknown>}
 */
export function listAvailability(technicianId) {
  return apiFetch(`/technicians/${technicianId}/availability`);
}

/**
 * @param {string} technicianId
 * @param {{ dayOfWeek: string, startTime: string, endTime: string }[]} windows
 * @param {{ idempotencyKey?: string }} [opts]
 * @returns {Promise<unknown>}
 */
export function setAvailability(technicianId, windows, { idempotencyKey } = {}) {
  return apiFetch(`/technicians/${technicianId}/availability`, {
    method: 'PUT',
    body: JSON.stringify({ windows }),
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
  });
}

// ---- Certification types -------------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   code: string,
 *   displayName: string,
 *   regulated: boolean,
 *   defaultValidityMonths: number | null,
 *   active: boolean,
 * }} CertificationType
 */

/**
 * @param {{ page?: number, size?: number, sort?: string }} params
 * @returns {Promise<unknown>}
 */
export function listCertificationTypes({ page = 0, size = 25, sort } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (sort) qs.set('sort', sort);
  return apiFetch(`/certification-types?${qs}`);
}

/**
 * @param {{ code: string, displayName: string, regulated: boolean, defaultValidityMonths?: number }} body
 * @param {{ idempotencyKey?: string }} [opts]
 * @returns {Promise<unknown>}
 */
export function createCertificationType(body, { idempotencyKey } = {}) {
  return apiFetch('/certification-types', {
    method: 'POST',
    body: JSON.stringify(body),
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
  });
}

/**
 * @param {string} id
 * @param {{ displayName?: string, regulated?: boolean, defaultValidityMonths?: number }} body
 * @returns {Promise<unknown>}
 */
export function updateCertificationType(id, body) {
  return apiFetch(`/certification-types/${id}`, { method: 'PUT', body: JSON.stringify(body) });
}

/**
 * @param {string} id
 * @returns {Promise<unknown>}
 */
export function deactivateCertificationType(id) {
  return apiFetch(`/certification-types/${id}`, { method: 'DELETE' });
}

// ---- Technician certifications ------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   typeCode: string,
 *   typeDisplayName: string,
 *   regulated: boolean,
 *   certificateReference: string | null,
 *   issuedOn: string | null,
 *   expiresOn: string | null,
 *   current: boolean,
 *   daysUntilExpiry: number | null,
 * }} CertificationSummary
 *
 * NOTE: `current` and `daysUntilExpiry` are ALWAYS server-derived.
 * The client MUST NOT recompute or cache them under any circumstances.
 */

/**
 * @param {string} technicianId
 * @param {{ page?: number, size?: number, atDate?: string }} params
 * @returns {Promise<unknown>}
 */
export function listTechnicianCertifications(technicianId, { page = 0, size = 20, atDate } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (atDate) qs.set('atDate', atDate);
  return apiFetch(`/technicians/${technicianId}/certifications?${qs}`);
}

/**
 * Batch upsert certifications (validate-all-then-persist).
 * Caller MUST provide a stable idempotencyKey so retries cannot double-insert.
 *
 * @param {string} technicianId
 * @param {Array<{ typeCode: string, certificateReference?: string, issuedOn?: string, expiresOn?: string, issuingBody?: string }>} items
 * @param {{ idempotencyKey: string }} opts
 * @returns {Promise<unknown>}
 */
export function upsertTechnicianCertifications(technicianId, items, { idempotencyKey }) {
  return apiFetch(`/technicians/${technicianId}/certifications`, {
    method: 'PUT',
    body: JSON.stringify({ items }),
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

/**
 * Bulk eligibility query — which technicians hold current certs for ALL required types?
 *
 * @param {{ requiredTypeCodes: string[], atDate?: string, page?: number, size?: number }} params
 * @returns {Promise<unknown>}
 */
export function findEligibleTechnicians({ requiredTypeCodes, atDate, page = 0, size = 20 } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  for (const code of requiredTypeCodes) qs.append('requiredTypeCodes', code);
  if (atDate) qs.set('atDate', atDate);
  return apiFetch(`/technicians/eligible?${qs}`);
}
