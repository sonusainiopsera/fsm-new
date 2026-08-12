/**
 * @fileoverview Cached data age formatter — converts an age in milliseconds
 * to a human-readable string for display in the offline connectivity banner.
 *
 * The shift TTL is 12 hours. Ages beyond this should be treated as stale
 * and shown with an explicit warning by the caller.
 */

const MINUTE_MS = 60_000
const HOUR_MS = 60 * MINUTE_MS

export const SHIFT_TTL_MS = 12 * HOUR_MS

/**
 * Formats the age of a cached response for display.
 *
 * @param {number} ageMs  Age in milliseconds (must be >= 0)
 * @returns {string}      Human-readable age string
 */
export function formatCachedAge(ageMs) {
  if (ageMs < 0) return 'unknown'
  if (ageMs < MINUTE_MS) return 'just now'
  if (ageMs < HOUR_MS) {
    const minutes = Math.floor(ageMs / MINUTE_MS)
    return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  }
  const hours = Math.floor(ageMs / HOUR_MS)
  return `${hours} hour${hours === 1 ? '' : 's'} ago`
}

/**
 * Returns true when the cached data is older than the shift TTL (12 hours).
 *
 * @param {number} ageMs
 * @returns {boolean}
 */
export function isCacheExpired(ageMs) {
  return ageMs >= SHIFT_TTL_MS
}
