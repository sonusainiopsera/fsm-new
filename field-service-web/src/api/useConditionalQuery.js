/**
 * Conditional-polling query hook with ETag support.
 *
 * Sends If-None-Match with the stored ETag on every refetch.
 * Treats 304 (signalled by the http client as { _304: true }) as fresh:
 * no cache replacement, no re-render.
 *
 * Named presets:
 *   useDashboardQuery  — 30-second refetch for dashboard widgets
 *   usePortalQuery     — 60-second refetch for the customer portal
 */

import { useQuery, useQueryClient } from '@tanstack/react-query';

export const DASHBOARD_INTERVAL = 30_000;
export const PORTAL_INTERVAL    = 60_000;

/**
 * Per-query-key ETag store (module-scope; survives component unmount/remount).
 * @type {Map<string, string>}
 */
const _etagCache = new Map();

/**
 * @param {readonly unknown[]} queryKey
 * @returns {string}
 */
function _key(queryKey) {
  return JSON.stringify(queryKey);
}

/**
 * Stores an ETag for a query key after a successful fetch.
 * @param {readonly unknown[]} queryKey
 * @param {string | null} etag
 */
export function storeEtag(queryKey, etag) {
  if (etag) _etagCache.set(_key(queryKey), etag);
}

/**
 * Returns the stored ETag for a query key, or null.
 * @param {readonly unknown[]} queryKey
 * @returns {string | null}
 */
export function getStoredEtag(queryKey) {
  return _etagCache.get(_key(queryKey)) ?? null;
}

/**
 * Removes the stored ETag for a query key.
 * @param {readonly unknown[]} queryKey
 */
export function clearEtag(queryKey) {
  _etagCache.delete(_key(queryKey));
}

/**
 * Core conditional-polling hook.
 *
 * The queryFn receives { signal, ifNoneMatch } and must:
 * - Pass If-None-Match to the HTTP layer
 * - Return { _304: true } when the server responds 304
 * - Otherwise return the response data (and include _etag if available)
 *
 * @param {{
 *   queryKey: readonly unknown[],
 *   queryFn: (ctx: { signal: AbortSignal, ifNoneMatch: string | null }) => Promise<unknown>,
 *   refetchInterval: number,
 *   enabled?: boolean,
 *   staleBeyondMs?: number,
 * }} options
 */
export function useConditionalQuery({
  queryKey,
  queryFn,
  refetchInterval,
  enabled = true,
  staleBeyondMs,
}) {
  const qc = useQueryClient();

  const wrappedFn = async ({ signal }) => {
    const ifNoneMatch = getStoredEtag(queryKey);
    const result = await queryFn({ signal, ifNoneMatch });

    // Server responded 304 — treat as fresh, preserve cached data
    if (result && typeof result === 'object' && result._304) {
      if (result.etag) storeEtag(queryKey, result.etag);
      // Returning undefined keeps the cached value intact in TanStack Query
      return qc.getQueryData(queryKey);
    }

    // Store ETag from response for next conditional request
    if (result && typeof result === 'object' && result._etag) {
      storeEtag(queryKey, result._etag);
    }

    return result;
  };

  return useQuery({
    queryKey,
    queryFn: wrappedFn,
    refetchInterval,
    enabled,
    placeholderData: (prev) => prev,
    meta: { staleBeyondMs },
  });
}

/**
 * Dashboard variant: 30-second conditional ETag polling.
 * @param {Omit<Parameters<typeof useConditionalQuery>[0], 'refetchInterval'>} options
 */
export function useDashboardQuery(options) {
  return useConditionalQuery({ ...options, refetchInterval: DASHBOARD_INTERVAL });
}

/**
 * Portal variant: 60-second conditional ETag polling.
 * @param {Omit<Parameters<typeof useConditionalQuery>[0], 'refetchInterval'>} options
 */
export function usePortalQuery(options) {
  return useConditionalQuery({ ...options, refetchInterval: PORTAL_INTERVAL });
}
