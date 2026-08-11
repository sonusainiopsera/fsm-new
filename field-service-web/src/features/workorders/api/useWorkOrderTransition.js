/**
 * @fileoverview Mutation hook for work order lifecycle transitions.
 *
 * AC-5/AC-6: Sends the legalNextEvent token, Idempotency-Key header, and
 * expectedVersion. On success, invalidates only the affected row query and
 * the board list to avoid full-board churn.
 *
 * AC-6 error mapping (every refusal code surfaces a specific message):
 * - 409 WORK_ORDER_ILLEGAL_TRANSITION → stale board; caller should refetch
 * - 409 WORK_ORDER_VERSION_CONFLICT    → concurrent edit; prompt reload
 * - 422 WORK_ORDER_GUARD_REFUSED       → show server's specific message
 * - 403                                → permission denied
 *
 * @module features/workorders/api/useWorkOrderTransition
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from '../../../api/http.js'
import { generateAttemptKey } from '../../../lib/idempotency.js'

/**
 * @typedef {{
 *   workOrderId: string,
 *   event: string,
 *   expectedVersion: number,
 *   idempotencyKey?: string
 * }} TransitionInput
 */

/**
 * @typedef {{
 *   variant: 'illegal_transition' | 'version_conflict' | 'guard_refused' | 'permission' | 'unknown',
 *   message: string,
 *   raw: import('../../../api/errors.js').ClientError
 * }} TransitionError
 */

/**
 * Maps a ClientError from a transition call to a typed TransitionError.
 *
 * @param {import('../../../api/errors.js').ClientError} error
 * @returns {TransitionError}
 */
export function mapTransitionError(error) {
  if (error.status === 409) {
    if (error.code === 'WORK_ORDER_VERSION_CONFLICT') {
      return {
        variant: 'version_conflict',
        message: 'This work order was changed by another user. Please reload and try again.',
        raw: error,
      }
    }
    return {
      variant: 'illegal_transition',
      message: 'This action is no longer available. The board has been refreshed.',
      raw: error,
    }
  }
  if (error.status === 422) {
    return {
      variant: 'guard_refused',
      message: error.message || 'The operation was refused by a business rule.',
      raw: error,
    }
  }
  if (error.status === 403) {
    return {
      variant: 'permission',
      message: 'You do not have permission to perform this action.',
      raw: error,
    }
  }
  return {
    variant: 'unknown',
    message: error.message || 'An unexpected error occurred.',
    raw: error,
  }
}

/**
 * Mutation hook for issuing a work order lifecycle transition.
 *
 * @returns {import('@tanstack/react-query').UseMutationResult<void, TransitionError, TransitionInput>}
 */
export function useWorkOrderTransition() {
  const queryClient = useQueryClient()

  return useMutation({
    /**
     * @param {TransitionInput} input
     */
    mutationFn: async ({ workOrderId, event, expectedVersion, idempotencyKey }) => {
      const key = idempotencyKey ?? generateAttemptKey()
      await request(`/work-orders/${workOrderId}/transitions`, {
        method: 'POST',
        body: JSON.stringify({ event, expectedVersion }),
        headers: {
          'Idempotency-Key': key,
          'Content-Type': 'application/json',
        },
      })
    },

    onError: () => {
      // Error mapping is performed by the caller via mapTransitionError.
      // Do not re-throw here; TanStack Query v5 would produce a second
      // unhandled rejection. mutateAsync() already rejects with the original error.
    },

    onSuccess: (_data, { workOrderId }) => {
      // Invalidate only the affected row and the board list
      queryClient.invalidateQueries({ queryKey: ['workOrders', 'board'] })
      queryClient.invalidateQueries({ queryKey: ['workOrders', 'detail', workOrderId] })
    },
  })
}
