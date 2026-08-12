/**
 * @fileoverview TanStack Query hooks for the classification registry.
 *
 * @module features/privacy/hooks/useClassifications
 */
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { listClassifications, updateClassification } from '../api/privacyClient.js'
import { parsePagedEnvelope } from '../../../api/pagination.js'

/** @param {{ page: number, size: number, sort?: string }} params */
export function useClassifications({ page, size, sort } = {}) {
  const result = useQuery({
    queryKey: ['privacy', 'classifications', { page, size, sort }],
    queryFn: ({ signal }) => listClassifications({ page, size, sort, signal }),
    placeholderData: (prev) => prev,
  })

  const envelope = parsePagedEnvelope(result.data)

  return {
    rows: envelope.data,
    page: envelope.page,
    links: envelope.links,
    isLoading: result.isLoading,
    isFetching: result.isFetching,
    isError: result.isError,
    error: result.error ?? null,
    refetch: result.refetch,
  }
}

/**
 * Mutation hook for updating a classification.
 * Handles 409 conflict by returning the error — caller must refetch and re-apply.
 * @returns {import('@tanstack/react-query').UseMutationResult}
 */
export function useUpdateClassification() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }) => updateClassification(id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['privacy', 'classifications'] })
    },
  })
}
