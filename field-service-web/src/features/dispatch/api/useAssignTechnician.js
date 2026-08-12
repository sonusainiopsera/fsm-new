/**
 * @fileoverview TanStack mutation hooks for assigning and reassigning technicians.
 *
 * POST /api/v1/work-orders/{workOrderId}/assign
 * POST /api/v1/work-orders/{workOrderId}/reassignment
 *
 * Both endpoints receive an Idempotency-Key header generated once per
 * submission-attempt sequence and reused across user-initiated retries of the
 * same payload so a flaky connection cannot produce a duplicate assignment.
 *
 * @module features/dispatch/api/useAssignTechnician
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { post } from '../../../api/http.js'

// ── Constants ─────────────────────────────────────────────────────────────────

/** Server limit for override reason text (mirrors API validation). */
export const OVERRIDE_REASON_MAX_LEN = 1000

/** Server limit for appointment impact acknowledgement text. */
export const APPT_ACK_MAX_LEN = 2000

/** Server limit for reason notes. */
export const REASON_NOTES_MAX_LEN = 2000

/** Rank threshold: technicians ranked ≤ this do NOT require an override reason. */
export const OVERRIDE_RANK_THRESHOLD = 3

/** Controlled reassignment reason codes (mirrors server-side ReassignmentReason enum). */
export const REASSIGNMENT_REASONS = /** @type {const} */ ([
  { value: 'TECHNICIAN_UNAVAILABLE', label: 'Technician unavailable' },
  { value: 'JOB_OVERRUN',            label: 'Job overrun' },
  { value: 'SKILL_MISMATCH',         label: 'Skill mismatch' },
  { value: 'SLA_RISK',               label: 'SLA risk' },
  { value: 'CUSTOMER_REQUEST',       label: 'Customer request' },
  { value: 'PARTS_UNAVAILABLE',      label: 'Parts unavailable' },
  { value: 'OTHER',                  label: 'Other' },
])

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Returns true when the server-supplied rank requires an override reason.
 * Rank absent (null/undefined) → override required.
 * Rank > OVERRIDE_RANK_THRESHOLD → override required.
 * Rank ≤ OVERRIDE_RANK_THRESHOLD → no override required.
 *
 * @param {number | null | undefined} rank
 * @returns {boolean}
 */
export function isOverrideRequired(rank) {
  if (rank == null) return true
  return rank > OVERRIDE_RANK_THRESHOLD
}

/**
 * Generates a stable idempotency key for one dialog session.
 * Falls back to a pseudo-random hex string when crypto.randomUUID is unavailable.
 * @returns {string}
 */
export function generateDialogKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return Array.from({ length: 32 }, () =>
    Math.floor(Math.random() * 16).toString(16)
  ).join('')
}

// ── Types ─────────────────────────────────────────────────────────────────────

/**
 * @typedef {{
 *   technicianId: string,
 *   recommendationSnapshotId?: string | null,
 *   overrideReason?: string | null,
 *   expectedVersion?: number
 * }} AssignRequest
 */

/**
 * @typedef {{
 *   technicianId: string,
 *   reassignmentReason: string,
 *   reasonNotes?: string | null,
 *   recommendationSnapshotId?: string | null,
 *   overrideReason?: string | null,
 *   appointmentImpactAcknowledgement?: string | null,
 *   expectedVersion?: number
 * }} ReassignRequest
 */

/**
 * @typedef {{
 *   assignmentId: string,
 *   workOrderId: string,
 *   technicianId: string,
 *   state: string,
 *   assignedAt: string,
 *   overrideRecorded: boolean,
 *   appointmentImpactRecorded?: boolean,
 *   supersededAssignmentId?: string | null
 * }} AssignmentResponse
 */

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Mutation for the initial assignment flow (mode='assign').
 *
 * @param {{
 *   workOrderId: string,
 *   idempotencyKey: string,
 *   onSuccess?: (data: AssignmentResponse) => void,
 *   onError?: (err: import('../../../api/errors.js').ClientError) => void
 * }} opts
 */
export function useAssignTechnician({ workOrderId, idempotencyKey, onSuccess, onError }) {
  const queryClient = useQueryClient()

  return useMutation({
    /**
     * @param {AssignRequest} payload
     */
    mutationFn(payload) {
      return post(
        `/work-orders/${workOrderId}/assign`,
        payload,
        { headers: { 'Idempotency-Key': idempotencyKey } },
      )
    },
    onSuccess(data) {
      // Invalidate work-order detail + recommendations so UI reflects new state
      queryClient.invalidateQueries({ queryKey: ['workOrder', workOrderId] })
      queryClient.invalidateQueries({ queryKey: ['dispatch', 'recommendations', workOrderId] })
      onSuccess?.(data)
    },
    onError(err) {
      onError?.(/** @type {any} */ (err))
    },
    retry: false,
  })
}

/**
 * Mutation for the reassignment flow (mode='reassign').
 *
 * @param {{
 *   workOrderId: string,
 *   idempotencyKey: string,
 *   onSuccess?: (data: AssignmentResponse) => void,
 *   onError?: (err: import('../../../api/errors.js').ClientError) => void
 * }} opts
 */
export function useReassignTechnician({ workOrderId, idempotencyKey, onSuccess, onError }) {
  const queryClient = useQueryClient()

  return useMutation({
    /**
     * @param {ReassignRequest} payload
     */
    mutationFn(payload) {
      return post(
        `/work-orders/${workOrderId}/reassignment`,
        payload,
        { headers: { 'Idempotency-Key': idempotencyKey } },
      )
    },
    onSuccess(data) {
      queryClient.invalidateQueries({ queryKey: ['workOrder', workOrderId] })
      queryClient.invalidateQueries({ queryKey: ['dispatch', 'recommendations', workOrderId] })
      onSuccess?.(data)
    },
    onError(err) {
      onError?.(/** @type {any} */ (err))
    },
    retry: false,
  })
}
