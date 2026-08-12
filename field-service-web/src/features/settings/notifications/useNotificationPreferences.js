import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getNotificationPreferences,
  upsertNotificationPreferences,
} from '../../../api/notificationPreferences.js'

/**
 * @param {string} userId
 * @returns {readonly [string, string, string]}
 */
function makeQueryKey(userId) {
  return /** @type {const} */ (['settings', 'notification-preferences', userId])
}

/**
 * Fetches notification preferences for the given user.
 *
 * @param {string} userId
 * @returns {{
 *   data: import('../../../api/notificationPreferences.js').NotificationPreferencesResponse | undefined,
 *   isLoading: boolean,
 *   isError: boolean,
 *   error: Error | null,
 *   isStale: boolean,
 *   dataUpdatedAt: number,
 * }}
 */
export function useNotificationPreferences(userId) {
  const { data, isLoading, isError, error, isStale, dataUpdatedAt } = useQuery({
    queryKey: makeQueryKey(userId),
    queryFn: () => getNotificationPreferences(userId),
    enabled: Boolean(userId),
  })

  return {
    data,
    isLoading,
    isError,
    error: /** @type {Error | null} */ (error),
    isStale,
    dataUpdatedAt,
  }
}

/**
 * Mutation hook for upserting notification preferences with optimistic updates.
 *
 * @param {string} userId
 * @returns {import('@tanstack/react-query').UseMutationResult<
 *   import('../../../api/notificationPreferences.js').NotificationPreferencesResponse,
 *   Error,
 *   { preferences: Array<{ category: string, channel: string, enabled: boolean }> },
 *   { previousData: import('../../../api/notificationPreferences.js').NotificationPreferencesResponse | undefined }
 * >}
 */
export function useUpsertNotificationPreferences(userId) {
  const queryClient = useQueryClient()
  const queryKey = makeQueryKey(userId)

  return useMutation({
    /**
     * @param {{ preferences: Array<{ category: string, channel: string, enabled: boolean }> }} variables
     */
    mutationFn: (variables) => upsertNotificationPreferences(userId, variables),

    /**
     * Optimistic update: apply the changes locally before the server responds.
     * @param {{ preferences: Array<{ category: string, channel: string, enabled: boolean }> }} variables
     */
    onMutate: async (variables) => {
      // Cancel any in-flight refetches so they don't overwrite our optimistic update
      await queryClient.cancelQueries({ queryKey })

      // Snapshot the previous value for rollback
      const previousData =
        queryClient.getQueryData(/** @type {any} */ (queryKey))

      // Apply optimistic update
      queryClient.setQueryData(/** @type {any} */ (queryKey), (
        /** @type {import('../../../api/notificationPreferences.js').NotificationPreferencesResponse | undefined} */ old
      ) => {
        if (!old) return old
        const patchMap = new Map(
          variables.preferences.map((p) => [`${p.category}:${p.channel}`, p.enabled])
        )
        return {
          ...old,
          data: old.data.map((item) => {
            const key = `${item.category}:${item.channel}`
            const patched = patchMap.get(key)
            if (patched !== undefined) {
              return { ...item, enabled: patched, source: /** @type {'EXPLICIT'} */ ('EXPLICIT') }
            }
            return item
          }),
        }
      })

      return { previousData: /** @type {import('../../../api/notificationPreferences.js').NotificationPreferencesResponse | undefined} */ (previousData) }
    },

    /**
     * On error, roll back to the previous data.
     * @param {Error} _error
     * @param {{ preferences: Array<{ category: string, channel: string, enabled: boolean }> }} _variables
     * @param {{ previousData: import('../../../api/notificationPreferences.js').NotificationPreferencesResponse | undefined } | undefined} context
     */
    onError: (_error, _variables, context) => {
      if (context?.previousData !== undefined) {
        queryClient.setQueryData(/** @type {any} */ (queryKey), context.previousData)
      }
    },

    /**
     * Always refetch after error or success to sync with server state.
     */
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey })
    },
  })
}
