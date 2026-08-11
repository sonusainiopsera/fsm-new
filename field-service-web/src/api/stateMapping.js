/**
 * Maps a normalised ClientError and optional query metadata to one of the
 * WO-087 named state primitives used by <StateSurface />.
 *
 * Single translation point: screens never contain per-status branching.
 *
 * Named states: 'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'
 * Plus the extension 'rate-limited' used by this layer (rendered as degraded).
 */

/**
 * @typedef {'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'} StateSurfaceVariant
 */

/**
 * Maps a normalised error to a named state primitive.
 *
 * Status mapping:
 * - 403 → permission-denied (no existence disclosure)
 * - 429 → degraded (rate-limited; honour Retry-After suppression)
 * - 503 → degraded (provider unavailable)
 * - 5xx / network (0) → degraded (transient failure)
 * - 409 / 422 → error (domain rule violation; server message surfaced verbatim)
 * - 400 / 404 → error
 *
 * @param {import('./errors.js').ClientError | Error | null | undefined} error
 * @param {{ staleBeyondMs?: number, lastFetchedAt?: number | null }} [meta]
 * @returns {StateSurfaceVariant}
 */
export function mapErrorToState(error, meta = {}) {
  if (!error) {
    return _stalenessState(meta);
  }

  const status = (error && typeof error === 'object' && 'status' in error)
    ? error.status
    : 0;

  if (status === 403) return 'permission-denied';
  if (status === 429) return 'degraded';
  if (status === 503) return 'degraded';
  if (status === 0 || status >= 500) return _stalenessState(meta);
  // Domain rule violations: surface server message, do not retry
  if (status === 409 || status === 422) return 'error';
  // All other client errors
  return 'error';
}

/**
 * Returns 'degraded' if data is older than the stale threshold, else 'error'.
 * When no last-fetch timestamp is available, defaults to 'degraded' for 5xx/network
 * since we cannot confirm freshness.
 *
 * @param {{ staleBeyondMs?: number, lastFetchedAt?: number | null }} meta
 * @returns {StateSurfaceVariant}
 */
function _stalenessState({ staleBeyondMs, lastFetchedAt } = {}) {
  if (
    staleBeyondMs != null &&
    lastFetchedAt != null &&
    Date.now() - lastFetchedAt > staleBeyondMs
  ) {
    return 'degraded';
  }
  return 'degraded';
}

/**
 * Convenience helper for TanStack Query result objects.
 * Returns the appropriate named state, or null if the query is healthy.
 *
 * @param {{
 *   isLoading: boolean,
 *   isFetching: boolean,
 *   isError: boolean,
 *   error: unknown,
 *   dataUpdatedAt: number,
 * }} queryResult
 * @param {{ staleBeyondMs?: number }} [options]
 * @returns {StateSurfaceVariant | null}
 */
export function getQueryState(queryResult, options = {}) {
  const { isLoading, isFetching, isError, error, dataUpdatedAt } = queryResult;

  if (isLoading && isFetching) return 'loading';

  if (isError) {
    return mapErrorToState(
      /** @type {any} */ (error),
      { staleBeyondMs: options.staleBeyondMs, lastFetchedAt: dataUpdatedAt || null },
    );
  }

  if (
    options.staleBeyondMs &&
    dataUpdatedAt &&
    Date.now() - dataUpdatedAt > options.staleBeyondMs
  ) {
    return 'degraded';
  }

  return null;
}
