/**
 * @fileoverview Copilot stream state machine constants (WO-179).
 *
 * The hook exposes one of these states at all times.  The sheet renders
 * a distinct, named presentation for each one so there are no unnamed or
 * undefined visual states.
 *
 * State transitions (valid paths):
 *   idle         → streaming  (user submits a question)
 *   streaming    → complete   (complete event received)
 *   streaming    → refused    (no_grounded_basis event received)
 *   streaming    → degraded   (degraded event or stream error)
 *   streaming    → partial    (stream closes without a terminal event)
 *   idle         → degraded   (ticket fetch failed or EventSource refused to open)
 *   idle         → capped     (ticket fetch returns 429)
 *   complete     → idle       (user dismisses the sheet)
 *   refused      → idle       (user dismisses)
 *   degraded     → idle       (user dismisses)
 *   partial      → idle       (user dismisses)
 *   capped       → idle       (user dismisses)
 */

/** @enum {string} */
export const CopilotState = {
  /** No question submitted; entry point is visible. */
  IDLE:      'idle',
  /** Stream is open and token chunks are arriving. */
  STREAMING: 'streaming',
  /** Terminal success: answer is complete, basis attached, rating available. */
  COMPLETE:  'complete',
  /** Terminal: no grounded basis for an answer; no generated procedure shown. */
  REFUSED:   'refused',
  /** Terminal: provider unavailable or stream failed; retry is offered. */
  DEGRADED:  'degraded',
  /** Terminal: daily interaction cap reached; Retry-After guidance is shown. */
  CAPPED:    'capped',
  /** Terminal: stream ended without a complete event; partial text retained. */
  PARTIAL:   'partial',
}

/** States that represent a terminal answer (complete text visible). */
export const ANSWER_STATES = new Set([
  CopilotState.COMPLETE,
  CopilotState.PARTIAL,
])

/** States where the Advisory label must be displayed. */
export const ADVISORY_STATES = new Set([
  CopilotState.STREAMING,
  CopilotState.COMPLETE,
  CopilotState.PARTIAL,
])

/** States from which a manual retry is safe (provider-side error, not a deliberate refusal). */
export const RETRYABLE_STATES = new Set([
  CopilotState.DEGRADED,
  CopilotState.PARTIAL,
])

/**
 * Human-readable labels for each state — used in ARIA live-region announcements.
 * @type {Record<string, string>}
 */
export const STATE_ANNOUNCEMENT = {
  [CopilotState.STREAMING]: 'Answer is loading',
  [CopilotState.COMPLETE]:  'Answer ready',
  [CopilotState.REFUSED]:   'No guidance available for this job',
  [CopilotState.DEGRADED]:  'Copilot is temporarily unavailable',
  [CopilotState.CAPPED]:    'Daily interaction limit reached',
  [CopilotState.PARTIAL]:   'Answer may be incomplete',
}
