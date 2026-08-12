/**
 * Mutation hook for work order assignment and reassignment.
 *
 * Maintains a stable Idempotency-Key per submission sequence so retried
 * requests on flaky connections cannot double-assign.  Call
 * refreshIdempotencyKey() when starting a genuinely new submission attempt.
 */
import { useRef, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { post } from '../../../api/http.js';

/**
 * @typedef {{
 *   technicianId: string,
 *   recommendationSnapshotId?: string,
 *   overrideReason?: string,
 *   reassignmentReason?: string,
 *   reasonNotes?: string,
 *   appointmentImpactAcknowledgement?: string,
 * }} AssignPayload
 */

/**
 * @param {{ workOrderId: string, mode?: 'assign' | 'reassign' }} options
 * @returns {import('@tanstack/react-query').UseMutationResult<unknown, unknown, AssignPayload> & { refreshIdempotencyKey: () => string }}
 */
export function useAssignTechnician({ workOrderId, mode = 'assign' } = {}) {
  const qc = useQueryClient();
  const idempotencyKeyRef = useRef(/** @type {string | null} */ (null));

  const refreshIdempotencyKey = useCallback(() => {
    idempotencyKeyRef.current = crypto.randomUUID();
    return idempotencyKeyRef.current;
  }, []);

  function getCurrentKey() {
    if (!idempotencyKeyRef.current) {
      idempotencyKeyRef.current = crypto.randomUUID();
    }
    return idempotencyKeyRef.current;
  }

  const mutation = useMutation({
    mutationFn: /** @param {AssignPayload} payload */ (payload) => {
      const path = mode === 'reassign'
        ? `/work-orders/${workOrderId}/reassignment`
        : `/work-orders/${workOrderId}/assignment`;
      return post(path, payload, {
        headers: { 'Idempotency-Key': getCurrentKey() },
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['work-order-context', workOrderId] });
      qc.invalidateQueries({ queryKey: ['recommendations', workOrderId] });
    },
  });

  return { ...mutation, refreshIdempotencyKey };
}
