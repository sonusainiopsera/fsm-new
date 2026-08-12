/**
 * @fileoverview TanStack Query hooks for subject rectification and erasure.
 *
 * @module features/privacy/hooks/useSubjectRights
 */
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { rectifySubject, initiateErasure, getErasure } from '../api/privacyClient.js'

/** Fetch an existing erasure tombstone. */
export function useErasure(erasureId) {
  const result = useQuery({
    queryKey: ['privacy', 'erasure', erasureId],
    queryFn: ({ signal }) => getErasure(/** @type {string} */ (erasureId), { signal }),
    enabled: erasureId != null,
  })
  return {
    erasure: result.data ?? null,
    isLoading: result.isLoading,
    isError: result.isError,
    error: result.error ?? null,
  }
}

/** Mutation hook for field-level rectification. */
export function useRectification() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ subjectType, subjectId, body }) =>
      rectifySubject(subjectType, subjectId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['privacy', 'dsar-requests'] })
    },
    retry: false,
  })
}

/**
 * Mutation hook for initiating cryptographic erasure.
 *
 * AC-7/erasure idempotency: the server handles double-submission via the
 * idempotency key attached by the http layer.
 */
export function useErasureMutation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ subjectType, subjectId, body }) =>
      initiateErasure(subjectType, subjectId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['privacy', 'dsar-requests'] })
      qc.invalidateQueries({ queryKey: ['privacy', 'erasure'] })
    },
    retry: false,
  })
}
