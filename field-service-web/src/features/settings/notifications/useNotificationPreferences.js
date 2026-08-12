/**
 * TanStack Query hooks for per-user notification channel preferences.
 *
 * Fetch: GET /api/v1/users/{userId}/notification-preferences
 * Mutate: PUT /api/v1/users/{userId}/notification-preferences
 *
 * Authorization: self-or-ADMIN enforced server-side. A 403 from this
 * endpoint indicates no access — do not probe for existence disclosure.
 *
 * @module useNotificationPreferences
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../../api/http.js';

const BASE = '/api/v1/users';

/**
 * @typedef {'EMAIL' | 'SMS' | 'PUSH' | 'IN_APP'} NotificationChannel
 * @typedef {'DEFAULT' | 'EXPLICIT'} PreferenceSource
 *
 * @typedef {{
 *   category: string,
 *   channel: NotificationChannel,
 *   enabled: boolean,
 *   source: PreferenceSource,
 * }} PreferenceEntryDto
 *
 * @typedef {{
 *   preferences: Array<{ category: string, channel: NotificationChannel, enabled: boolean }>,
 * }} PreferenceUpdateRequest
 */

/**
 * Returns the query key for a user's notification preferences.
 * @param {string} userId
 * @returns {readonly unknown[]}
 */
export function preferenceQueryKey(userId) {
  return /** @type {const} */ (['notification-preferences', userId]);
}

/**
 * Fetches notification preferences for a user.
 *
 * @param {{
 *   userId: string,
 *   enabled?: boolean,
 * }} options
 * @returns {import('@tanstack/react-query').UseQueryResult}
 */
export function useNotificationPreferences({ userId, enabled = true } = {}) {
  return useQuery({
    queryKey: preferenceQueryKey(userId),
    queryFn: ({ signal }) =>
      apiFetch(`${BASE}/${userId}/notification-preferences`, { signal }),
    enabled: enabled && Boolean(userId),
    staleTime: 60_000,
    retry: (failureCount, error) => {
      // Do not retry auth errors — 403 means no access, not a transient failure
      if (error?.status === 403 || error?.status === 401) return false;
      return failureCount < 2;
    },
  });
}

/**
 * Optimistic mutation to upsert notification preferences for a user.
 *
 * On success, the cache for the user is invalidated so the list refreshes.
 * On failure, the optimistic update is rolled back to the pre-mutation snapshot.
 *
 * @param {string} userId
 * @returns {import('@tanstack/react-query').UseMutationResult}
 */
export function useUpsertNotificationPreferences(userId) {
  const queryClient = useQueryClient();
  const key = preferenceQueryKey(userId);

  return useMutation({
    mutationFn: (/** @type {PreferenceUpdateRequest} */ body) =>
      apiFetch(`${BASE}/${userId}/notification-preferences`, {
        method: 'PUT',
        body: JSON.stringify(body),
        headers: { 'Content-Type': 'application/json' },
      }),

    onMutate: async (newPrefs) => {
      await queryClient.cancelQueries({ queryKey: key });
      const snapshot = queryClient.getQueryData(key);

      // Optimistically merge new values into cached data
      queryClient.setQueryData(key, (old) => {
        if (!old) return old;
        const byKey = new Map(
          (old.data ?? []).map((e) => [`${e.category}:${e.channel}`, e])
        );
        for (const entry of newPrefs.preferences) {
          byKey.set(`${entry.category}:${entry.channel}`, {
            ...entry,
            source: 'EXPLICIT',
          });
        }
        return { ...old, data: Array.from(byKey.values()) };
      });

      return { snapshot };
    },

    onError: (_err, _vars, context) => {
      if (context?.snapshot !== undefined) {
        queryClient.setQueryData(key, context.snapshot);
      }
    },

    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: key });
    },
  });
}
