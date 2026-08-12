/**
 * useCreateWorkOrder — mutation hook for work order creation.
 *
 * Idempotency contract (WO-131 AC-5):
 * - A single idempotency key is generated when the hook initialises (modal open).
 * - That key is re-used on every retry of the same submission.
 * - Calling reset() generates a fresh key so the next submission is a new attempt.
 *
 * @module features/workorders/api/useCreateWorkOrder
 */

import { useRef } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch } from '../../../api/http.js';
import { newAttemptKey } from '../../../lib/idempotency.js';

/**
 * @typedef {{
 *   customerId: string,
 *   siteId: string,
 *   assetId?: string | null,
 *   faultDescription: string,
 *   priority: string,
 *   requiredCertificationCodes?: string[],
 *   expectedParts?: Array<{ partId: string, quantity: number }>,
 * }} CreateWorkOrderPayload
 */

/**
 * @typedef {{
 *   id: string,
 *   reference: string,
 *   state: string,
 *   priority: string,
 *   responseDeadline: string | null,
 *   resolutionDeadline: string | null,
 *   atRisk: boolean,
 * }} CreatedWorkOrder
 */

/**
 * @returns {{
 *   submit: (payload: CreateWorkOrderPayload) => void,
 *   isSubmitting: boolean,
 *   isSuccess: boolean,
 *   data: CreatedWorkOrder | null,
 *   error: import('../../../api/errors.js').ClientError | null,
 *   reset: () => void,
 * }}
 */
export function useCreateWorkOrder() {
  const queryClient  = useQueryClient();
  // Stable key for the current submission attempt — regenerated on reset().
  const idempotencyKeyRef = useRef(newAttemptKey());

  const mutation = useMutation({
    mutationFn: (/** @type {CreateWorkOrderPayload} */ payload) =>
      apiFetch('/work-orders', {
        method: 'POST',
        body: JSON.stringify(payload),
        headers: {
          'Idempotency-Key': idempotencyKeyRef.current,
        },
      }),

    onSuccess: () => {
      // Invalidate the board so the new row appears without a full reload.
      queryClient.invalidateQueries({ queryKey: ['work-orders'] });
    },
  });

  function reset() {
    mutation.reset();
    idempotencyKeyRef.current = newAttemptKey();
  }

  return {
    submit:      mutation.mutate,
    isSubmitting: mutation.isPending,
    isSuccess:   mutation.isSuccess,
    data:        mutation.data ?? null,
    error:       mutation.error ?? null,
    reset,
  };
}
