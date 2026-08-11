/**
 * @fileoverview Maps SSE event types to the TanStack Query keys they invalidate.
 *
 * When an at-risk or breach event arrives, the mapped keys are invalidated so
 * affected views refetch within the sub-two-second alert budget.
 */

/**
 * @typedef {{ type: string, workOrderId?: string, payload?: unknown }} SseEvent
 */

/**
 * Maps event type → array of query key prefixes to invalidate.
 * Partial match: invalidating ['workOrders'] invalidates all keys that start
 * with 'workOrders' (e.g. ['workOrders', 'list'], ['workOrders', id]).
 *
 * @type {Record<string, string[][]>}
 */
export const EVENT_KEY_MAP = {
  WorkOrderAtRisk: [['workOrders'], ['dashboard', 'atRisk']],
  WorkOrderBreached: [['workOrders'], ['dashboard', 'breached']],
  WorkOrderStateChanged: [['workOrders']],
  PartsConsumed: [['workOrders'], ['stock']],
  PartsReturned: [['workOrders'], ['stock']],
  StockTransferred: [['stock']],
  StockAdjusted: [['stock']],
}

/**
 * Returns the query key prefixes to invalidate for the given event type.
 *
 * @param {string} eventType
 * @returns {string[][]}  Array of query key prefix arrays
 */
export function getInvalidationKeys(eventType) {
  return EVENT_KEY_MAP[eventType] ?? []
}
