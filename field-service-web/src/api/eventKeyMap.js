/**
 * SSE event type → TanStack Query key invalidation map.
 *
 * When an at-risk or breach alert arrives the affected query keys are
 * invalidated so views refetch without hand-written state code.
 * Invalidation must happen within the sub-two-second alert budget.
 */

import { queryClient } from './queryClient.js';

/**
 * Query key arrays to invalidate per SSE event type.
 * A single event may invalidate multiple query families.
 *
 * @type {Readonly<Record<string, ReadonlyArray<readonly unknown[]>>>}
 */
export const EVENT_KEY_MAP = Object.freeze({
  'at-risk':       [['work-orders', 'list'], ['work-orders', 'at-risk']],
  'breach':        [['work-orders', 'list'], ['work-orders', 'breach']],
  'state-change':  [['work-orders', 'list']],
  'parts-consumed':[['inventory',  'stock']],
  'parts-returned':[['inventory',  'stock']],
});

/**
 * Handles a raw SSE event by invalidating the mapped query keys immediately.
 * Uses synchronous invalidation (no await) to stay within the alert budget.
 *
 * @param {{ type: string, data?: string }} event
 */
export function handleSseEvent(event) {
  // Resolve the event type: named events carry their type directly;
  // generic 'message' events may encode the type inside the JSON payload.
  const type = event.type === 'message' ? _parseEventType(event.data) : event.type;
  const keys = EVENT_KEY_MAP[type];
  if (!keys) return;

  for (const queryKey of keys) {
    queryClient.invalidateQueries({ queryKey: [...queryKey] });
  }
}

/**
 * Returns the query keys registered for a given event type.
 * Useful for assertion in tests.
 *
 * @param {string} eventType
 * @returns {ReadonlyArray<readonly unknown[]> | undefined}
 */
export function getInvalidationKeys(eventType) {
  return EVENT_KEY_MAP[eventType];
}

/**
 * @param {string | undefined} data
 * @returns {string}
 */
function _parseEventType(data) {
  if (!data) return '';
  try {
    const parsed = JSON.parse(data);
    return parsed.eventType ?? parsed.type ?? '';
  } catch (_) {
    return data;
  }
}
