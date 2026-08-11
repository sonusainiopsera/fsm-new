/**
 * @fileoverview As-of freshness evaluation for BR-15 compliance.
 *
 * Any data surface that displays server-provided inventory counts, stock
 * balances, or alert states must call evaluateFreshness() with the server's
 * as-of timestamp and, if the result is DEGRADED, render the named degraded
 * state naming WHAT is stale and HOW stale, rather than presenting the
 * figures as current.
 *
 * The 60-second staleness budget is defined by BR-15 and is not a tunable
 * parameter — changing it requires a re-deploy and product sign-off.
 */

/** BR-15 staleness budget in milliseconds. */
export const STALENESS_BUDGET_MS = 60_000

/**
 * @typedef {'FRESH' | 'DEGRADED' | 'UNKNOWN'} FreshnessStatus
 */

/**
 * @typedef {{
 *   status: FreshnessStatus,
 *   stalenessMs: number,
 *   staleSince: Date | null,
 *   humanDescription: string
 * }} FreshnessResult
 */

/**
 * Evaluates the freshness of data with the given as-of timestamp.
 *
 * @param {string | Date | null | undefined} asOf  ISO-8601 string or Date from the server response
 * @param {Date} [now]  Current time (defaults to new Date(); injectable for tests)
 * @returns {FreshnessResult}
 */
export function evaluateFreshness(asOf, now = new Date()) {
  if (!asOf) {
    return {
      status: 'UNKNOWN',
      stalenessMs: 0,
      staleSince: null,
      humanDescription: 'Data freshness unknown — no timestamp received.',
    }
  }

  const asOfDate = asOf instanceof Date ? asOf : new Date(asOf)
  if (isNaN(asOfDate.getTime())) {
    return {
      status: 'UNKNOWN',
      stalenessMs: 0,
      staleSince: null,
      humanDescription: 'Data freshness unknown — timestamp could not be parsed.',
    }
  }

  const stalenessMs = now.getTime() - asOfDate.getTime()
  if (stalenessMs < 0) {
    // Server clock slightly ahead — treat as fresh
    return { status: 'FRESH', stalenessMs: 0, staleSince: asOfDate, humanDescription: 'Data is current.' }
  }

  if (stalenessMs > STALENESS_BUDGET_MS) {
    return {
      status: 'DEGRADED',
      stalenessMs,
      staleSince: asOfDate,
      humanDescription: buildDegradedDescription(stalenessMs),
    }
  }

  return {
    status: 'FRESH',
    stalenessMs,
    staleSince: asOfDate,
    humanDescription: 'Data is current.',
  }
}

/**
 * Returns true when the given freshness result means the UI should render
 * the degraded state rather than the live figures.
 *
 * @param {FreshnessResult} result
 * @returns {boolean}
 */
export function isDegraded(result) {
  return result.status === 'DEGRADED'
}

/**
 * Formats a duration in milliseconds as a human-readable "N seconds / N minutes ago" string.
 *
 * @param {number} ms
 * @returns {string}
 */
export function formatStaleDuration(ms) {
  const seconds = Math.floor(ms / 1000)
  if (seconds < 90) return `${seconds} second${seconds === 1 ? '' : 's'} ago`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
}

/**
 * Builds the degraded description message shown to the user.
 * Names how stale the data is (BR-15: must say what is stale and how stale).
 *
 * @param {number} stalenessMs
 * @returns {string}
 */
function buildDegradedDescription(stalenessMs) {
  const duration = formatStaleDuration(stalenessMs)
  return `This data was last refreshed ${duration}. Live figures may differ.`
}
