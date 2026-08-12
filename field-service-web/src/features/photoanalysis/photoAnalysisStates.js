/**
 * Explicit state machine constants for the photo analysis lifecycle.
 *
 * State transitions:
 *   IDLE       → UPLOADING (capture triggered)
 *   UPLOADING  → ANALYZING (upload complete, analysis started)
 *   UPLOADING  → DEGRADED  (upload failed)
 *   ANALYZING  → SUGGESTED (AI draft returned)
 *   ANALYZING  → DEGRADED  (AI unavailable, flag off, or cap reached)
 *   SUGGESTED  → EDITING   (technician modifies the suggestion)
 *   SUGGESTED  → DISCARDED (technician clears the field)
 *   EDITING    → SUBMITTED (technician submits description)
 *   DISCARDED  → SUBMITTED (technician submits empty or new description)
 *   any        → IDLE      (sheet dismissed)
 *
 * @module features/photoanalysis/photoAnalysisStates
 */

/** No photo capture in progress. */
export const IDLE       = 'IDLE';

/** Photo is being uploaded to object storage. */
export const UPLOADING  = 'UPLOADING';

/** Upload complete; AI analysis in progress. */
export const ANALYZING  = 'ANALYZING';

/**
 * AI suggestion received — pre-filled editable description field visible.
 * The suggestion is advisory only; the technician's text is authoritative.
 */
export const SUGGESTED  = 'SUGGESTED';

/** Technician is editing the pre-filled suggestion. */
export const EDITING    = 'EDITING';

/** Technician cleared the suggestion (or it was blank). Plain field shown. */
export const DISCARDED  = 'DISCARDED';

/** Technician has submitted a description. */
export const SUBMITTED  = 'SUBMITTED';

/**
 * Provider unavailable, daily cap reached, or feature flag off.
 * A plain editable description field is shown — the technician can
 * complete the job without AI assistance.
 */
export const DEGRADED   = 'DEGRADED';

/** States in which the suggestion field should show the AI-suggested chip. */
export const STATES_WITH_SUGGESTION = new Set([SUGGESTED, EDITING]);

/** Terminal states — no further analysis action is possible. */
export const TERMINAL_STATES = new Set([SUBMITTED, DEGRADED]);

/** Degraded error codes returned by the analysis endpoint. */
export const DEGRADED_CODES = {
  AI_PROVIDER_UNAVAILABLE : 'AI_PROVIDER_UNAVAILABLE',
  AI_DAILY_LIMIT_REACHED  : 'AI_DAILY_LIMIT_REACHED',
  AI_FEATURE_DISABLED     : 'AI_FEATURE_DISABLED',
};
