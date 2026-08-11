/**
 * @fileoverview Conditional-polling hook with ETag/If-None-Match support.
 *
 * Behaviours:
 * - Sends If-None-Match with the stored ETag on every poll.
 * - Treats 304 as "fresh": the cached data is kept as-is, no re-render, no
 *   cache replacement, and the staleness clock resets.
 * - Exposes two named polling presets:
 *     DASHBOARD_INTERVAL: 30 s (dispatcher / ops widgets)
 *     PORTAL_INTERVAL:    60 s (customer portal)
 * - On each successful (200) response the ETag is extracted and stored in
 *   module scope keyed on the query key string.
 */

import { useQuery } from '@tanstack/react-query'
import { getToken } from './tokenStore.js'

export const DASHBOARD_INTERVAL = 30_000
export const PORTAL_INTERVAL = 60_000

/** ETag store: queryKey → etag string */
const _etags = new Map()

/**
 * Returns the stored ETag for the given query key array.
 * @param {unknown[]} queryKey
 * @returns {string | undefined}
 */
export function getETag(queryKey) {
  return _etags.get(JSON.stringify(queryKey))
}

/**
 * Stores an ETag for the given query key array.
 * @param {unknown[]} queryKey
 * @param {string} etag
 */
export function setETag(queryKey, etag) {
  _etags.set(JSON.stringify(queryKey), etag)
}

/**
 * Clears all stored ETags (test utility).
 * @internal
 */
export function _clearETagsForTesting() {
  _etags.clear()
}

/**
 * A TanStack Query hook that performs conditional GET polling.
 *
 * On a 304 response the fetch function returns `undefined` (the sentinel for
 * "no data change"), and TanStack Query keeps the existing cached data.
 *
 * @template T
 * @param {{
 *   queryKey: unknown[],
 *   url: string,
 *   refetchInterval?: number,
 *   enabled?: boolean,
 *   select?: (data: T) => unknown,
 *   signal?: AbortSignal
 * }} options
 * @returns {import('@tanstack/react-query').UseQueryResult<T>}
 */
export function useConditionalQuery({ queryKey, url, refetchInterval = DASHBOARD_INTERVAL, enabled = true, select }) {
  return useQuery({
    queryKey,
    queryFn: ({ signal }) => conditionalFetch(url, queryKey, signal),
    refetchInterval,
    enabled,
    select,
    // Keep the previous data when a 304 returns undefined
    placeholderData: (prev) => prev,
  })
}

/**
 * Performs a conditional GET request, honouring If-None-Match / ETag.
 *
 * @template T
 * @param {string} url
 * @param {unknown[]} queryKey
 * @param {AbortSignal} [signal]
 * @returns {Promise<T | undefined>}
 */
export async function conditionalFetch(url, queryKey, signal) {
  const token = getToken()
  const etag = getETag(queryKey)

  const headers = new Headers({
    Accept: 'application/json',
  })
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (etag) headers.set('If-None-Match', etag)

  const response = await fetch(url, { headers, signal })

  if (response.status === 304) {
    // Fresh: return undefined so TanStack Query keeps the existing cached data
    return undefined
  }

  if (!response.ok) {
    const { normaliseError } = await import('./errors.js')
    let body = null
    try { body = await response.json() } catch { /* ignore */ }
    throw normaliseError(response.status, body)
  }

  // Store the new ETag if present
  const newETag = response.headers.get('ETag')
  if (newETag) setETag(queryKey, newETag)

  return response.json()
}
