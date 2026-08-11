/**
 * @fileoverview Maps a normalised ClientError + query meta to the WO-087 named
 * state primitives so screens render the correct StateSurface variant without
 * per-screen branching.
 *
 * Named states: 'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'
 */

/**
 * @typedef {import('./errors.js').ClientError} ClientError
 * @typedef {'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'} SurfaceVariant
 */

/**
 * @typedef {{
 *   variant: SurfaceVariant,
 *   message?: string,
 *   retryAfterMs?: number
 * }} StateMapping
 */

/**
 * Maps a ClientError to the appropriate StateSurface variant and optional message.
 *
 * Mapping rules:
 * - 403 → permission-denied  (no existence disclosure — message is always generic)
 * - 401 → permission-denied  (treated as unauthenticated; callers should redirect)
 * - 429 → rate-limited (degraded variant with retryAfterMs from Retry-After header)
 * - 503 → degraded            (provider unavailable)
 * - 409 / 422 → error with server message surfaced verbatim
 * - All others → error with generic message
 *
 * @param {ClientError} error
 * @param {{ retryAfterMs?: number }} [meta]
 * @returns {StateMapping}
 */
export function mapErrorToState(error, meta = {}) {
  switch (error.status) {
    case 403:
    case 401:
      return { variant: 'permission-denied' }

    case 429:
      return {
        variant: 'degraded',
        message: 'Rate limit reached. Retrying shortly.',
        retryAfterMs: meta.retryAfterMs,
      }

    case 503:
      return {
        variant: 'degraded',
        message: 'A required service is temporarily unavailable.',
      }

    case 409:
    case 422:
      // Surface the server message verbatim for domain errors
      return {
        variant: 'error',
        message: error.message,
      }

    default:
      return {
        variant: 'error',
        message: error.status === 0
          ? 'Unable to connect. Check your network connection.'
          : undefined,
      }
  }
}

/**
 * Maps TanStack Query meta to a loading or degraded state.
 *
 * @param {{
 *   isFetching: boolean,
 *   isStale: boolean,
 *   dataUpdatedAt: number,
 *   stalenessWindowMs?: number
 * }} queryState
 * @returns {SurfaceVariant | null} null when no special state applies
 */
export function mapQueryToState({ isFetching, isStale, dataUpdatedAt, stalenessWindowMs = 120_000 }) {
  if (isFetching && dataUpdatedAt === 0) {
    return 'loading'
  }
  if (isStale && dataUpdatedAt > 0) {
    const ageMs = Date.now() - dataUpdatedAt
    if (ageMs > stalenessWindowMs) {
      return 'degraded'
    }
  }
  return null
}
