/**
 * @fileoverview TanStack Query hooks for notification preference endpoints (WO-197).
 *
 * Data: GET/PUT /api/v1/users/{userId}/notification-preferences
 * Optimistic: PUT applies the update to the cache immediately and rolls back on error.
 *
 * @typedef {{ category: string, channel: string, enabled: boolean, source: 'DEFAULT'|'EXPLICIT' }} PreferenceItem
 * @typedef {{ data: PreferenceItem[], page: {number:number,size:number,totalElements:number,totalPages:number}, links: {next:string|null,prev:string|null} }} PreferencesResponse
 * @typedef {{ category: string, channel: string, enabled: boolean }} PreferenceUpdate
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getToken } from '../../../../api/tokenStore.js'

const BASE = '/api/v1'

/**
 * Builds the preferences URL for a given user.
 * @param {string} userId
 * @param {{ page?: number, size?: number }} [pagination]
 * @returns {string}
 */
export function buildPreferencesUrl(userId, { page = 0, size = 50 } = {}) {
  const clampedSize = Math.min(50, Math.max(1, size))
  return `${BASE}/users/${encodeURIComponent(userId)}/notification-preferences?page=${page}&size=${clampedSize}`
}

/**
 * Fetches notification preferences for a user.
 * @param {string} userId
 * @returns {Promise<PreferencesResponse>}
 */
async function fetchPreferences(userId) {
  const url = buildPreferencesUrl(userId)
  const token = getToken()
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    const err = new Error(body.message ?? 'Failed to load preferences')
    err.status = res.status
    err.code = body.code
    throw err
  }
  return res.json()
}

/**
 * Puts updated preferences for a user.
 * @param {string} userId
 * @param {PreferenceUpdate[]} preferences
 * @returns {Promise<PreferencesResponse>}
 */
async function putPreferences(userId, preferences) {
  const url = buildPreferencesUrl(userId)
  const token = getToken()
  const res = await fetch(url, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ preferences }),
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    const err = new Error(body.message ?? 'Failed to save preferences')
    err.status = res.status
    err.code = body.code
    err.fieldErrors = body.fieldErrors
    throw err
  }
  return res.json()
}

// ── Query key factory ─────────────────────────────────────────────────────────

export const notificationPreferenceKeys = {
  all: /** @returns {string[]} */ () => ['notificationPreferences'],
  user: /** @param {string} userId @returns {string[]} */ (userId) =>
    ['notificationPreferences', userId],
}

// ── useNotificationPreferences ────────────────────────────────────────────────

/**
 * Fetches the notification preferences for a user.
 *
 * @param {string|null} userId  — null/undefined disables the query
 * @returns {import('@tanstack/react-query').UseQueryResult<PreferencesResponse>}
 */
export function useNotificationPreferences(userId) {
  return useQuery({
    queryKey: notificationPreferenceKeys.user(userId ?? ''),
    queryFn: () => fetchPreferences(userId),
    enabled: Boolean(userId),
    staleTime: 30_000,
    retry: 1,
  })
}

// ── useUpdateNotificationPreferences ─────────────────────────────────────────

/**
 * Mutation to upsert notification preferences with optimistic update and rollback.
 *
 * @param {string} userId
 * @returns {import('@tanstack/react-query').UseMutationResult}
 */
export function useUpdateNotificationPreferences(userId) {
  const queryClient = useQueryClient()
  const key = notificationPreferenceKeys.user(userId)

  return useMutation({
    mutationFn: (/** @type {PreferenceUpdate[]} */ preferences) =>
      putPreferences(userId, preferences),

    onMutate: async (preferences) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData(key)

      // Optimistic: merge the pending updates into the cached response
      queryClient.setQueryData(key, (/** @type {PreferencesResponse|undefined} */ old) => {
        if (!old) return old
        const updated = old.data.map((item) => {
          const match = preferences.find(
            (p) => p.category === item.category && p.channel === item.channel
          )
          if (match) return { ...item, enabled: match.enabled, source: 'EXPLICIT' }
          return item
        })
        // Append new items not yet in cache
        const newItems = preferences
          .filter(
            (p) =>
              !old.data.some(
                (item) => item.category === p.category && item.channel === p.channel
              )
          )
          .map((p) => ({ ...p, source: 'EXPLICIT' }))
        return { ...old, data: [...updated, ...newItems] }
      })

      return { previous }
    },

    onError: (_err, _vars, context) => {
      if (context?.previous !== undefined) {
        queryClient.setQueryData(key, context.previous)
      }
    },

    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: key })
    },
  })
}
