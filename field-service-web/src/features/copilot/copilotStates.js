/**
 * Explicit state machine constants for the copilot stream lifecycle.
 *
 * State transitions:
 *   IDLE → STREAMING (ticket fetched, EventSource opened)
 *   STREAMING → COMPLETE (complete event received)
 *   STREAMING → REFUSED (no_grounded_basis event received)
 *   STREAMING → DEGRADED (degraded event, EventSource error, or ticket fetch failure)
 *   STREAMING → CAPPED (429 on ticket endpoint)
 *   STREAMING → PARTIAL (EventSource closed without a terminal event, partial content present)
 *   any → IDLE (sheet dismissed)
 *
 * @module features/copilot/copilotStates
 */

/** Copilot has not been invoked yet — sheet is closed. */
export const IDLE       = 'IDLE';

/** Streaming in progress — token events arriving. */
export const STREAMING  = 'STREAMING';

/** Streaming completed successfully. */
export const COMPLETE   = 'COMPLETE';

/** Provider returned no grounded basis — no fabricated answer shown. */
export const REFUSED    = 'REFUSED';

/** Provider unavailable or stream failed (network or provider error). */
export const DEGRADED   = 'DEGRADED';

/** Daily limit reached — Retry-After guidance shown. */
export const CAPPED     = 'CAPPED';

/**
 * Stream closed without a terminal event and there is partial content.
 * The partial content is shown clearly marked as incomplete.
 */
export const PARTIAL    = 'PARTIAL';

/** All terminal states where no retry would produce new grounded content. */
export const TERMINAL_NO_RETRY = new Set([REFUSED]);

/** States in which an answer (or partial answer) is shown and rating should appear. */
export const RATEABLE_STATES = new Set([COMPLETE, PARTIAL]);
