/**
 * @fileoverview Mutation hook for work order creation and SLA policy query.
 *
 * AC-3: SLA policy sourced from the server, never hard-coded.
 * AC-5: Idempotency-Key passed by caller; the same key must be reused on retry.
 * AC-6: On success, board query is invalidated so the new row appears.
 *
 * Error mapping:
 *   400 fieldErrors    → mapped onto CreateWorkOrderError.fieldErrors by field path
 *   422 SLA_POLICY_MISSING        → specific actionable message
 *   422 SITE_CUSTOMER_MISMATCH    → specific message
 *   422 ASSET_SITE_MISMATCH       → specific message
 *   429                            → retryAfterSecs populated from Retry-After header
 *   5xx / network                  → generic with idempotencyKey retained for retry
 *
 * @module features/workorders/api/useCreateWorkOrder
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '../../../api/http.js'

// ── SLA policy lookup ─────────────────────────────────────────────────────────

export const slaPolicyKeys = {
  /** @param {string} priority */
  byPriority: (priority) => ['slaPolicy', priority],
}

/**
 * @typedef {{
 *   priority: string,
 *   responseMins: number,
 *   resolutionMins: number,
 *   atRiskFraction: number
 * }} SlaPolicyData
 */

/**
 * Fetches the service-level policy for a given priority tier.
 * Returns the response and resolution minutes and the at-risk fraction
 * so the modal can preview deadlines before submission.
 *
 * @param {string | null | undefined} priority
 * @returns {import('@tanstack/react-query').UseQueryResult<SlaPolicyData>}
 */
export function useSlaPolicyByPriority(priority) {
  return useQuery({
    queryKey: slaPolicyKeys.byPriority(priority ?? ''),
    queryFn: () => request(`/sla-policies/${priority}`),
    enabled: Boolean(priority),
    staleTime: 5 * 60 * 1000, // 5 minutes — policies change infrequently
  })
}

// ── Work order creation ───────────────────────────────────────────────────────

/**
 * @typedef {{
 *   customerId: string,
 *   siteId: string,
 *   assetId?: string | null,
 *   title: string,
 *   faultDescription: string,
 *   priority: string,
 *   requiredCertificationTypeCodes?: string[],
 *   expectedPartSkus?: string[],
 *   idempotencyKey: string
 * }} CreateWorkOrderInput
 */

/**
 * @typedef {{
 *   id: string,
 *   reference: string,
 *   responseDueAt: string | null,
 *   resolutionDueAt: string | null,
 *   state: string,
 *   priority: string
 * }} CreatedWorkOrder
 */

/**
 * @typedef {{
 *   variant: 'field_errors' | 'policy_missing' | 'mismatch' | 'rate_limited' | 'server' | 'network',
 *   message: string,
 *   fieldErrors?: Record<string, string[]>,
 *   retryAfterSecs?: number,
 *   raw: unknown
 * }} CreateWorkOrderError
 */

/**
 * Maps a ClientError from the creation call to a typed CreateWorkOrderError.
 *
 * @param {import('../../../api/errors.js').ClientError} err
 * @returns {CreateWorkOrderError}
 */
export function mapCreateError(err) {
  if (err.status === 400) {
    // Server 400: map fieldErrors array → Record<field, string[]>
    const fieldErrors = /** @type {Record<string, string[]>} */ ({})
    const raw = /** @type {any} */ (err)
    const serverFieldErrors = raw.fieldErrors ?? raw.errors ?? []
    for (const fe of serverFieldErrors) {
      const field = fe.field ?? fe.path ?? 'general'
      if (!fieldErrors[field]) fieldErrors[field] = []
      fieldErrors[field].push(fe.message ?? fe.defaultMessage ?? String(fe))
    }
    return {
      variant: 'field_errors',
      message: 'Please correct the highlighted fields.',
      fieldErrors,
      raw: err,
    }
  }

  if (err.status === 422) {
    const code = /** @type {any} */ (err).code ?? ''
    if (code === 'SLA_POLICY_MISSING') {
      return {
        variant: 'policy_missing',
        message: 'No SLA policy is configured for this priority tier. Ask your administrator to add one before creating a work order.',
        raw: err,
      }
    }
    if (code === 'SITE_CUSTOMER_MISMATCH') {
      return {
        variant: 'mismatch',
        message: 'The selected site does not belong to the selected customer. Please choose a matching site.',
        raw: err,
      }
    }
    if (code === 'ASSET_SITE_MISMATCH') {
      return {
        variant: 'mismatch',
        message: 'The selected asset does not belong to the selected site. Please choose a matching asset.',
        raw: err,
      }
    }
    return {
      variant: 'mismatch',
      message: err.message || 'A business rule prevented this work order from being created.',
      raw: err,
    }
  }

  if (err.status === 429) {
    const retryAfter = /** @type {any} */ (err).retryAfterSecs ?? 60
    return {
      variant: 'rate_limited',
      message: `Too many requests. Please wait ${retryAfter} seconds before retrying.`,
      retryAfterSecs: retryAfter,
      raw: err,
    }
  }

  if (!err.status || err.status >= 500) {
    return {
      variant: 'server',
      message: 'A server error occurred. Your form data has been preserved — you can retry using the same submission.',
      raw: err,
    }
  }

  return {
    variant: 'network',
    message: 'Could not reach the server. Please check your connection and try again.',
    raw: err,
  }
}

/**
 * Mutation hook for creating a work order.
 *
 * The caller is responsible for generating and storing the idempotency key
 * (once per modal open, reused on retry). Do not pass a new key on retry.
 *
 * @returns {import('@tanstack/react-query').UseMutationResult<CreatedWorkOrder, CreateWorkOrderError, CreateWorkOrderInput>}
 */
export function useCreateWorkOrder() {
  const queryClient = useQueryClient()

  return useMutation({
    /**
     * @param {CreateWorkOrderInput} input
     */
    mutationFn: async ({ idempotencyKey, ...payload }) => {
      return request('/work-orders', {
        method: 'POST',
        body: JSON.stringify(payload),
        headers: {
          // Explicit key — http.js will not override because the header is already set
          'Idempotency-Key': idempotencyKey,
          'Content-Type': 'application/json',
        },
      })
    },

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workOrders', 'board'] })
    },
  })
}
