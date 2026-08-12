/**
 * @fileoverview Photo analysis state machine constants (WO-181).
 *
 * The useAnalyzePhoto hook exposes one of these states at all times.
 *
 * State transitions:
 *   idle      → loading   (technician taps "Analyse")
 *   loading   → ready     (AI draft received)
 *   loading   → degraded  (provider unavailable or capped)
 *   loading   → disabled  (feature flag off)
 *   ready     → idle      (user discards the suggestion)
 *   *         → idle      (user submits description — field unmounts)
 */

/** @enum {string} */
export const PhotoAnalysisState = {
  /** No analysis in progress; analyse button visible. */
  IDLE:     'idle',
  /** Analysis request in flight. */
  LOADING:  'loading',
  /** AI draft received; suggestion pre-fills the description field. */
  READY:    'ready',
  /** Provider unavailable or daily cap reached; user can proceed without AI. */
  DEGRADED: 'degraded',
  /** Feature flag is off; analysis button not rendered. */
  DISABLED: 'disabled',
}
