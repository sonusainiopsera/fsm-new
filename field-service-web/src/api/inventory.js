/**
 * Typed API client for inventory endpoints.
 *
 * Covers WO-053 (parts logging / returns), WO-054 (movement history),
 * WO-055 (stock positions), WO-056 (low-stock alerts), and work-order
 * state transition (AWAITING_PARTS hold).
 *
 * All GET calls accept an optional ifNoneMatch for ETag-conditional polling.
 * Mutating calls accept an idempotencyKey that the caller generates once per
 * attempt (see src/lib/idempotency.js) and reuses verbatim on retries.
 *
 * @module api/inventory
 */

import { apiFetch } from './http.js';

// ---- Stock positions (WO-055) -------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   partId: string,
 *   partNumber: string,
 *   partDescription: string,
 *   locationId: string,
 *   locationName: string,
 *   locationType: 'WAREHOUSE' | 'VEHICLE',
 *   quantityOnHand: number,
 *   reorderPoint: number | null,
 *   stockStatus: 'OK' | 'LOW' | 'OUT',
 *   asOf: string,
 * }} StockPosition
 */

/**
 * @typedef {{
 *   data: StockPosition[],
 *   page: { number: number, size: number, totalElements: number, totalPages: number },
 *   _links: object,
 *   asOf: string,
 * }} StockPositionPage
 */

/**
 * Lists stock positions with server-side pagination (max 50).
 *
 * @param {{ page?: number, size?: number, sort?: string, locationId?: string, ifNoneMatch?: string | null }} params
 * @returns {Promise<StockPositionPage>}
 */
export async function listStockPositions({ page = 0, size = 50, sort, locationId, ifNoneMatch } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (sort) qs.set('sort', sort);
  if (locationId) qs.set('locationId', locationId);

  return apiFetch(`/inventory/stock?${qs}`, {
    method: 'GET',
    headers: ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : {},
  });
}

// ---- Movement history (WO-054) -----------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   partId: string,
 *   partNumber: string,
 *   locationId: string,
 *   movementType: string,
 *   quantity: number,
 *   reasonCode: string,
 *   workOrderId: string | null,
 *   occurredAt: string,
 *   performedBy: string,
 * }} MovementRecord
 */

/**
 * @typedef {{
 *   data: MovementRecord[],
 *   page: { number: number, size: number, totalElements: number, totalPages: number },
 *   _links: object,
 * }} MovementPage
 */

/**
 * Lists movement history filtered by part and/or location.
 *
 * @param {{ partId?: string, locationId?: string, page?: number, size?: number, ifNoneMatch?: string | null }} params
 * @returns {Promise<MovementPage>}
 */
export async function listMovements({ partId, locationId, page = 0, size = 50, ifNoneMatch } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });
  if (partId) qs.set('partId', partId);
  if (locationId) qs.set('locationId', locationId);

  return apiFetch(`/inventory/movements?${qs}`, {
    method: 'GET',
    headers: ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : {},
  });
}

// ---- Low-stock alerts (WO-056) ------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   partId: string,
 *   partNumber: string,
 *   partDescription: string,
 *   locationId: string,
 *   locationName: string,
 *   quantityOnHand: number,
 *   reorderPoint: number,
 *   stockStatus: 'LOW' | 'OUT',
 *   raisedAt: string,
 *   asOf: string,
 * }} StockAlert
 */

/**
 * @typedef {{
 *   data: StockAlert[],
 *   page: { number: number, size: number, totalElements: number, totalPages: number },
 *   _links: object,
 *   asOf: string,
 * }} StockAlertPage
 */

/**
 * Lists active low-stock and stockout alerts.
 *
 * @param {{ page?: number, size?: number, ifNoneMatch?: string | null }} params
 * @returns {Promise<StockAlertPage>}
 */
export async function listStockAlerts({ page = 0, size = 50, ifNoneMatch } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(Math.min(size, 50)) });

  return apiFetch(`/inventory/alerts?${qs}`, {
    method: 'GET',
    headers: ifNoneMatch ? { 'If-None-Match': ifNoneMatch } : {},
  });
}

// ---- Part search (WO-053) -----------------------------------------------

/**
 * @typedef {{
 *   id: string,
 *   partNumber: string,
 *   description: string,
 *   unitOfMeasure: string,
 *   availableQuantity: number,
 * }} PartSearchResult
 */

/**
 * Searches parts by number or description.
 *
 * @param {{ q: string, locationId?: string, signal?: AbortSignal }} params
 * @returns {Promise<{ data: PartSearchResult[] }>}
 */
export async function searchParts({ q, locationId, signal } = {}) {
  const qs = new URLSearchParams({ q: q ?? '' });
  if (locationId) qs.set('locationId', locationId);
  return apiFetch(`/inventory/parts/search?${qs}`, { method: 'GET', signal });
}

// ---- Parts logging / consumption (WO-053) --------------------------------

/**
 * @typedef {{
 *   partId: string,
 *   quantity: number,
 *   reasonCode: string,
 * }} ConsumptionLine
 */

/**
 * @typedef {{
 *   workOrderId: string,
 *   lines: ConsumptionLine[],
 * }} LogPartsRequest
 */

/**
 * Submits a multi-line parts consumption log for a work order.
 *
 * @param {LogPartsRequest} body
 * @param {string} idempotencyKey  Per-attempt UUID — reuse on retries, regenerate for new attempts
 * @returns {Promise<unknown>}
 */
export async function logParts(body, idempotencyKey) {
  return apiFetch('/inventory/consumptions', {
    method: 'POST',
    body: JSON.stringify(body),
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

// ---- Parts returns (WO-053) ----------------------------------------------

/**
 * @typedef {{
 *   workOrderId: string,
 *   lines: ConsumptionLine[],
 * }} ReturnPartsRequest
 */

/**
 * Submits a multi-line parts return for a work order.
 *
 * @param {ReturnPartsRequest} body
 * @param {string} idempotencyKey
 * @returns {Promise<unknown>}
 */
export async function returnParts(body, idempotencyKey) {
  return apiFetch('/inventory/returns', {
    method: 'POST',
    body: JSON.stringify(body),
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

// ---- Work-order state transition (AWAITING_PARTS hold) ------------------

/**
 * Places a work order on hold with the AWAITING_PARTS reason.
 *
 * @param {string} workOrderId
 * @param {string} idempotencyKey
 * @returns {Promise<unknown>}
 */
export async function placeOnAwaitingPartsHold(workOrderId, idempotencyKey) {
  return apiFetch(`/work-orders/${workOrderId}/transitions`, {
    method: 'POST',
    body: JSON.stringify({ transition: 'HOLD', reason: 'AWAITING_PARTS' }),
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
