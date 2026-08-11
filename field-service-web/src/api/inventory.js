/**
 * @fileoverview Typed API client for inventory endpoints.
 *
 * Covers WO-053 (parts catalog / consumption), WO-054 (movement ledger),
 * WO-055 (stock positions), and WO-056 (low-stock alerts).
 *
 * All endpoints are wired through the shared http.js transport which:
 * - Attaches the in-memory Bearer token
 * - Auto-generates an Idempotency-Key on POST/PUT/PATCH/DELETE
 * - Handles 401 → silent refresh → retry
 * - Normalises errors into ClientError
 *
 * Mutation callers that need retry-safe idempotency must supply an explicit
 * Idempotency-Key header (from src/lib/idempotency.js) so retries reuse the
 * same key rather than generating a new one per call.
 */

import { get, post } from './http.js'

// ── JSDoc types ───────────────────────────────────────────────────────────────

/**
 * @typedef {{
 *   partId: string,
 *   partNumber: string,
 *   description: string,
 *   unitOfMeasure: string,
 *   active: boolean
 * }} PartRecord
 */

/**
 * @typedef {{
 *   locationId: string,
 *   locationName: string,
 *   locationType: 'WAREHOUSE' | 'VEHICLE',
 *   partId: string,
 *   partNumber: string,
 *   partDescription: string,
 *   quantityOnHand: number,
 *   reorderPoint: number | null,
 *   asOf: string
 * }} StockPositionRecord
 */

/**
 * @typedef {{
 *   ledgerId: string,
 *   partId: string,
 *   partNumber: string,
 *   locationId: string,
 *   locationName: string,
 *   movementType: string,
 *   delta: number,
 *   balanceAfter: number,
 *   workOrderId: string | null,
 *   actorUserId: string,
 *   occurredAt: string,
 *   correlationId: string | null
 * }} MovementRecord
 */

/**
 * @typedef {{
 *   alertId: string,
 *   partId: string,
 *   partNumber: string,
 *   partDescription: string,
 *   locationId: string,
 *   locationName: string,
 *   locationType: 'WAREHOUSE' | 'VEHICLE',
 *   alertType: 'LOW_STOCK' | 'STOCKOUT',
 *   quantityOnHand: number,
 *   reorderPoint: number,
 *   raisedAt: string
 * }} StockAlertRecord
 */

/**
 * @typedef {{
 *   partId: string,
 *   quantity: number,
 *   reasonCode: string
 * }} ConsumptionLine
 */

/**
 * @typedef {{
 *   lines: ConsumptionLine[],
 *   workOrderId: string
 * }} ConsumePartsCommand
 */

/**
 * @typedef {{
 *   lines: ConsumptionLine[],
 *   workOrderId: string
 * }} ReturnPartsCommand
 */

/**
 * @typedef {{
 *   workOrderId: string,
 *   consumedLines: ConsumptionLine[],
 *   returnedLines: ConsumptionLine[]
 * }} WorkOrderPartsRecord
 */

// ── Parts catalog ─────────────────────────────────────────────────────────────

/**
 * Searches parts by number or description.
 * Used in the debounced part search in PartsLoggingPanel.
 *
 * @param {{ query: string, page?: number, size?: number, signal?: AbortSignal }} params
 * @returns {Promise<import('./pagination.js').PagedResponse<PartRecord>>}
 */
export function searchParts({ query, page = 0, size = 20, signal }) {
  const qs = new URLSearchParams({ query, page: String(page), size: String(size) })
  return get(`/inventory/parts/search?${qs}`, { signal })
}

// ── Stock positions ───────────────────────────────────────────────────────────

/**
 * Lists stock positions filtered by optional location type.
 * Server-paginated, 50-item ceiling, allow-listed sort.
 *
 * @param {{ locationType?: string, page?: number, size?: number, sort?: string, signal?: AbortSignal }} params
 * @returns {Promise<import('./pagination.js').PagedResponse<StockPositionRecord>>}
 */
export function listStockPositions({ locationType, page = 0, size = 50, sort, signal } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) })
  if (locationType) qs.set('locationType', locationType)
  if (sort) qs.set('sort', sort)
  return get(`/inventory/stock?${qs}`, { signal })
}

// ── Movement history ──────────────────────────────────────────────────────────

/**
 * Lists stock ledger movements, filterable by part, location, and work order.
 *
 * @param {{ partId?: string, locationId?: string, workOrderId?: string, page?: number, size?: number, signal?: AbortSignal }} params
 * @returns {Promise<import('./pagination.js').PagedResponse<MovementRecord>>}
 */
export function listMovements({ partId, locationId, workOrderId, page = 0, size = 50, signal } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) })
  if (partId) qs.set('partId', partId)
  if (locationId) qs.set('locationId', locationId)
  if (workOrderId) qs.set('workOrderId', workOrderId)
  return get(`/inventory/movements?${qs}`, { signal })
}

// ── Low-stock alerts ──────────────────────────────────────────────────────────

/**
 * Lists active low-stock and stockout alerts.
 *
 * @param {{ locationId?: string, alertType?: string, page?: number, size?: number, signal?: AbortSignal }} params
 * @returns {Promise<import('./pagination.js').PagedResponse<StockAlertRecord>>}
 */
export function listAlerts({ locationId, alertType, page = 0, size = 50, signal } = {}) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) })
  if (locationId) qs.set('locationId', locationId)
  if (alertType) qs.set('alertType', alertType)
  return get(`/inventory/alerts?${qs}`, { signal })
}

// ── Parts consumption (technician) ────────────────────────────────────────────

/**
 * Atomically consumes parts against a work order.
 * All lines are applied or none are (server-side atomicity).
 *
 * IMPORTANT: pass the caller-held idempotency key in options.headers so
 * retries of the same attempt use the same key (AC-6).
 *
 * @param {ConsumePartsCommand} command
 * @param {{ headers?: Record<string, string>, signal?: AbortSignal }} [options]
 * @returns {Promise<WorkOrderPartsRecord>}
 */
export function consumeParts(command, options = {}) {
  return post(`/work-orders/${command.workOrderId}/parts/consume`, command, options)
}

/**
 * Returns parts from a work order back to the source location.
 *
 * @param {ReturnPartsCommand} command
 * @param {{ headers?: Record<string, string>, signal?: AbortSignal }} [options]
 * @returns {Promise<WorkOrderPartsRecord>}
 */
export function returnParts(command, options = {}) {
  return post(`/work-orders/${command.workOrderId}/parts/return`, command, options)
}

// ── Work-order parts read ─────────────────────────────────────────────────────

/**
 * Gets the parts recorded against a work order.
 *
 * @param {{ workOrderId: string, signal?: AbortSignal }} params
 * @returns {Promise<WorkOrderPartsRecord>}
 */
export function getWorkOrderParts({ workOrderId, signal }) {
  return get(`/work-orders/${workOrderId}/parts`, { signal })
}

// ── Work-order transition (place on hold) ─────────────────────────────────────

/**
 * Places a work order on hold with the AWAITING_PARTS reason code.
 * Called from the 422 INSUFFICIENT_STOCK error surface.
 *
 * @param {{ workOrderId: string, expectedVersion: number }} params
 * @param {{ signal?: AbortSignal }} [options]
 * @returns {Promise<unknown>}
 */
export function placeOnAwaitingPartsHold({ workOrderId, expectedVersion }, options = {}) {
  return post(`/work-orders/${workOrderId}/transitions`, {
    event: 'HOLD',
    holdReasonCode: 'AWAITING_PARTS',
    expectedVersion,
  }, options)
}
