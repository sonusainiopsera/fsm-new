/**
 * useWorkOrderTransition — transition mutation hook.
 *
 * Posts a lifecycle event to POST /api/v1/work-orders/{id}/transitions with:
 * - Idempotency-Key generated per attempt
 * - expectedVersion for optimistic concurrency
 *
 * On success, invalidates the affected row query and the board list.
 *
 * Error mapping (per WO-130 contract):
 * - 409 WORK_ORDER_ILLEGAL_TRANSITION  → prompt board refresh
 * - 409 WORK_ORDER_VERSION_CONFLICT    → prompt page reload with explanation
 * - 422 WORK_ORDER_GUARD_REFUSED       → show guard's specific missing-evidence message
 * - 429 RATE_LIMITED                   → show retry-after countdown
 * - 5xx                                → non-blocking retry banner
 *
 * @module features/workorders/api/useWorkOrderTransition
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../../api/http.js';

/**
 * @typedef {'ILLEGAL_TRANSITION' | 'VERSION_CONFLICT' | 'GUARD_REFUSED' | 'RATE_LIMITED' | 'SERVER_ERROR' | 'NETWORK_ERROR'} RefusalClass
 *
 * @typedef {{
 *   type: RefusalClass,
 *   message: string,
 *   legalNextEvents?: string[],
 *   retryAfterMs?: number | null,
 *   traceId?: string | null,
 * }} TransitionRefusal
 */

/**
 * Maps a ClientError from the transitions endpoint to a typed refusal object.
 *
 * @param {import('../../../api/errors.js').ClientError} error
 * @returns {TransitionRefusal}
 */
export function classifyTransitionError(error) {
  const code = error?.code ?? '';

  if (error?.status === 409 && code === 'WORK_ORDER_VERSION_CONFLICT') {
    return {
      type: 'VERSION_CONFLICT',
      message: 'This work order was updated by someone else. Reload the page to see the latest state.',
      traceId: error.traceId ?? null,
    };
  }

  if (error?.status === 409) {
    // Illegal transition — legalNextEvents may be in fieldErrors
    const legalNextEvents = error.fieldErrors
      ?.find((fe) => fe.field === 'legalNextEvents')
      ?.message
      ?.split(',')
      .map((s) => s.trim())
      .filter(Boolean) ?? [];
    return {
      type: 'ILLEGAL_TRANSITION',
      message: 'This action is no longer available. The work order may have changed.',
      legalNextEvents,
      traceId: error.traceId ?? null,
    };
  }

  if (error?.status === 422) {
    const guardMessage = error.fieldErrors?.[0]?.message ?? error.message ?? 'A required condition was not met.';
    return {
      type: 'GUARD_REFUSED',
      message: guardMessage,
      traceId: error.traceId ?? null,
    };
  }

  if (error?.status === 429) {
    return {
      type: 'RATE_LIMITED',
      message: 'Too many requests. Please wait before retrying.',
      retryAfterMs: error.retryAfterMs ?? null,
      traceId: error.traceId ?? null,
    };
  }

  if (!error?.retryable && error?.status > 0) {
    return {
      type: 'ILLEGAL_TRANSITION',
      message: error.message ?? 'The action could not be completed.',
      traceId: error.traceId ?? null,
    };
  }

  if (error?.status === 0) {
    return {
      type: 'NETWORK_ERROR',
      message: 'Network error. Check your connection and try again.',
    };
  }

  return {
    type: 'SERVER_ERROR',
    message: 'A server error occurred. Please try again shortly.',
    traceId: error?.traceId ?? null,
  };
}

/**
 * @param {{
 *   onSuccess?: (data: unknown, variables: TransitionVariables) => void,
 *   onRefusal?: (refusal: TransitionRefusal, variables: TransitionVariables) => void,
 * }} [options]
 *
 * @typedef {{
 *   workOrderId: string,
 *   event: string,
 *   expectedVersion: number,
 *   holdReasonCode?: string | null,
 *   note?: string | null,
 * }} TransitionVariables
 */
export function useWorkOrderTransition(options = {}) {
  const qc = useQueryClient();
  const { onSuccess, onRefusal } = options;

  const mutation = useMutation({
    /**
     * @param {TransitionVariables} variables
     */
    mutationFn: async ({ workOrderId, event, expectedVersion, holdReasonCode, note }) => {
      const body = { event, expectedVersion };
      if (holdReasonCode) body.holdReasonCode = holdReasonCode;
      if (note) body.note = note;

      return apiFetch(`/work-orders/${workOrderId}/transitions`, {
        method: 'POST',
        body: JSON.stringify(body),
        // Idempotency-Key is injected automatically by apiFetch for POST
      });
    },

    onSuccess: (data, variables) => {
      // Invalidate this specific work order + the board list
      qc.invalidateQueries({ queryKey: ['work-orders', 'detail', variables.workOrderId] });
      qc.invalidateQueries({ queryKey: ['work-orders', 'board'] });
      onSuccess?.(data, variables);
    },

    onError: (error, variables) => {
      const refusal = classifyTransitionError(error);

      if (refusal.type === 'ILLEGAL_TRANSITION' || refusal.type === 'VERSION_CONFLICT') {
        // Refresh the affected row so the UI shows the current legal events
        qc.invalidateQueries({ queryKey: ['work-orders', 'detail', variables.workOrderId] });
        qc.invalidateQueries({ queryKey: ['work-orders', 'board'] });
      }

      onRefusal?.(refusal, variables);
    },
  });

  return {
    transition: mutation.mutate,
    transitionAsync: mutation.mutateAsync,
    isPending: mutation.isPending,
    refusal: mutation.error ? classifyTransitionError(mutation.error) : null,
    reset: mutation.reset,
  };
}
