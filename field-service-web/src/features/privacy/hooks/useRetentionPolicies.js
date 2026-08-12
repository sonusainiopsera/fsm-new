/**
 * @fileoverview TanStack Query hooks for retention policies and dry-run.
 *
 * @module features/privacy/hooks/useRetentionPolicies
 */
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { listRetentionPolicies, updateRetentionPolicy, runRetentionDryRun } from '../api/privacyClient.js'
import { parsePagedEnvelope } from '../../../api/pagination.js'

/** @param {{ page: number, size: number }} [params] */
export function useRetentionPolicies({ page = 0, size = 50 } = {}) {
  const result = useQuery({
    queryKey: ['privacy', 'retention-policies', { page, size }],
    queryFn: ({ signal }) => listRetentionPolicies({ page, size, signal }),
    placeholderData: (prev) => prev,
  })

  const envelope = parsePagedEnvelope(result.data)

  return {
    rows: envelope.data,
    page: envelope.page,
    isLoading: result.isLoading,
    isError: result.isError,
    error: result.error ?? null,
    refetch: result.refetch,
  }
}

/**
 * Mutation hook for updating a retention policy period.
 * Caller must handle 409 conflict by refetching the row.
 */
export function useUpdateRetentionPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }) => updateRetentionPolicy(id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['privacy', 'retention-policies'] })
    },
  })
}

/**
 * Mutation hook for the retention dry-run.
 * Returns eligible count, oldest eligible timestamp, cut-off instant and disposal method.
 * No data is changed.
 */
export function useRetentionDryRun() {
  return useMutation({
    mutationFn: ({ id }) => runRetentionDryRun(id),
    retry: false,
  })
}
