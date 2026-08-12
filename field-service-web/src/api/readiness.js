/**
 * API client for certification data-readiness report endpoints (WO-122).
 *
 * @module api/readiness
 */

import { apiFetch } from './http.js';

const BASE = '/reports/certification-readiness';

/**
 * @typedef {{
 *   readinessPercent: number | null,
 *   completeTechnicians: number,
 *   activeTechnicians: number,
 *   gateTarget: number,
 *   gateMet: boolean,
 *   blockingTechnicianCount: number,
 *   definitionVersion: string,
 *   evaluatedAt: string,
 *   applicable: boolean,
 * }} ReadinessAggregate
 */

/**
 * @typedef {{
 *   technicianId: string,
 *   employeeCode: string | null,
 *   displayName: string | null,
 *   missingFields: string[],
 *   missingCertificationTypes: string[],
 *   expiredCertificationTypes: string[],
 *   expiringSoonCertificationTypes: string[],
 * }} TechnicianGap
 */

/**
 * @typedef {{
 *   id: string,
 *   isoWeek: string,
 *   readinessPercent: number | null,
 *   completeTechnicians: number,
 *   activeTechnicians: number,
 *   gateMet: boolean,
 *   definitionVersion: string,
 *   generatedAt: string,
 * }} ReadinessSnapshot
 */

/**
 * @typedef {{
 *   id: string,
 *   requirementKind: 'PROFILE_FIELD' | 'CERTIFICATION_TYPE',
 *   fieldName: string | null,
 *   certificationTypeCode: string | null,
 *   technicianCategory: string | null,
 *   active: boolean,
 * }} ReadinessRequirement
 */

/**
 * Fetch the current readiness aggregate.
 *
 * @param {{ atDate?: string, warningWindowDays?: number }} [params]
 * @returns {Promise<ReadinessAggregate>}
 */
export async function getReadiness(params = {}) {
  const query = new URLSearchParams();
  if (params.atDate)            query.set('atDate', params.atDate);
  if (params.warningWindowDays) query.set('warningWindowDays', String(params.warningWindowDays));
  const qs = query.toString() ? `?${query}` : '';
  return apiFetch(`${BASE}${qs}`);
}

/**
 * Fetch a page of blocking technician gaps.
 *
 * @param {{ atDate?: string, warningWindowDays?: number, page?: number, size?: number }} [params]
 * @returns {Promise<{ data: TechnicianGap[], page: object, links: object }>}
 */
export async function getGaps(params = {}) {
  const query = new URLSearchParams();
  if (params.atDate)            query.set('atDate', params.atDate);
  if (params.warningWindowDays) query.set('warningWindowDays', String(params.warningWindowDays));
  if (params.page != null)      query.set('page', String(params.page));
  if (params.size != null)      query.set('size', String(params.size));
  const qs = query.toString() ? `?${query}` : '';
  return apiFetch(`${BASE}/gaps${qs}`);
}

/**
 * Returns the URL for the CSV gaps download (opened directly in browser).
 *
 * @param {{ atDate?: string, warningWindowDays?: number }} [params]
 * @returns {string}
 */
export function gapsCsvUrl(params = {}) {
  const query = new URLSearchParams();
  if (params.atDate)            query.set('atDate', params.atDate);
  if (params.warningWindowDays) query.set('warningWindowDays', String(params.warningWindowDays));
  const qs = query.toString() ? `?${query}` : '';
  return `/api/v1${BASE}/gaps.csv${qs}`;
}

/**
 * Fetch a page of weekly snapshots, newest first.
 *
 * @param {{ page?: number, size?: number }} [params]
 * @returns {Promise<{ data: ReadinessSnapshot[], page: object, links: object }>}
 */
export async function listSnapshots(params = {}) {
  const query = new URLSearchParams();
  if (params.page != null) query.set('page', String(params.page));
  if (params.size != null) query.set('size', String(params.size));
  const qs = query.toString() ? `?${query}` : '';
  return apiFetch(`${BASE}/snapshots${qs}`);
}

/**
 * Trigger (or re-generate) the snapshot for the current ISO week. ADMIN only.
 *
 * @param {{ atDate?: string, warningWindowDays?: number }} [params]
 * @returns {Promise<ReadinessSnapshot>}
 */
export async function triggerSnapshot(params = {}) {
  const query = new URLSearchParams();
  if (params.atDate)            query.set('atDate', params.atDate);
  if (params.warningWindowDays) query.set('warningWindowDays', String(params.warningWindowDays));
  const qs = query.toString() ? `?${query}` : '';
  return apiFetch(`${BASE}/snapshots${qs}`, { method: 'POST' });
}

/**
 * List all readiness requirements. ADMIN only.
 *
 * @returns {Promise<ReadinessRequirement[]>}
 */
export async function listRequirements() {
  return apiFetch('/readiness-requirements');
}

/**
 * Create a new readiness requirement. ADMIN only.
 *
 * @param {{ requirementKind: string, fieldName?: string, certificationTypeCode?: string, technicianCategory?: string }} body
 * @returns {Promise<ReadinessRequirement>}
 */
export async function createRequirement(body) {
  return apiFetch('/readiness-requirements', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
