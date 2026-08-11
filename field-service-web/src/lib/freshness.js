/**
 * Freshness / staleness evaluation helper.
 *
 * BR-15: when data exceeds 60 seconds of staleness the UI must enter the
 * named degraded state and show what is stale and how stale. Stale numbers
 * must NEVER be presented as current.
 */

/** Staleness budget in milliseconds before the degraded state triggers. */
export const STALENESS_BUDGET_MS = 60_000;

/**
 * Evaluates whether a server-provided as-of timestamp is still fresh.
 *
 * @param {string | null | undefined} asOf  ISO-8601 string from the server
 * @param {number} [nowMs]                  Override for testability; defaults to Date.now()
 * @returns {{ isFresh: boolean, ageMs: number, ageSeconds: number }}
 */
export function evaluateFreshness(asOf, nowMs = Date.now()) {
  if (!asOf) {
    return { isFresh: false, ageMs: Infinity, ageSeconds: Infinity };
  }
  const serverMs = new Date(asOf).getTime();
  if (Number.isNaN(serverMs)) {
    return { isFresh: false, ageMs: Infinity, ageSeconds: Infinity };
  }
  const ageMs = nowMs - serverMs;
  const ageSeconds = Math.floor(ageMs / 1000);
  return {
    isFresh: ageMs <= STALENESS_BUDGET_MS,
    ageMs,
    ageSeconds,
  };
}

/**
 * Formats a human-readable staleness description for use in the DegradedState.
 *
 * @param {string} dataLabel   e.g. "Stock positions"
 * @param {number} ageSeconds  Seconds since the as-of timestamp
 * @returns {string}
 */
export function staleDescription(dataLabel, ageSeconds) {
  if (!Number.isFinite(ageSeconds)) {
    return `${dataLabel} data freshness is unknown. Live updates may be unavailable.`;
  }
  if (ageSeconds < 120) {
    return `${dataLabel} data is ${ageSeconds} seconds old. Live updates may be unavailable.`;
  }
  const minutes = Math.floor(ageSeconds / 60);
  return `${dataLabel} data is ${minutes} minute${minutes === 1 ? '' : 's'} old. Live updates may be unavailable.`;
}
