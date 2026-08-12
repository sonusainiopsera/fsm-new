/**
 * @fileoverview TanStack Query hooks for the DSAR queue.
 *
 * Countdown derivation: remaining days computed from server-supplied dueAt
 * and current date. atRisk flag is server-supplied — the client does NOT
 * recompute the threshold to avoid disagreeing with the compliance metric.
 *
 * @module features/privacy/hooks/useDsarRequests
 */
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listDsarRequests,
  getDsarRequest,
  transitionDsarRequest,
  getDsarExportUrl,
} from '../api/privacyClient.js'
import { parsePagedEnvelope } from '../../../api/pagination.js'

/**
 * @param {{ page?: number, size?: number, sort?: string, state?: string }} [params]
 */
export function useDsarRequests({ page = 0, size = 50, sort, state } = {}) {
  const result = useQuery({
    queryKey: ['privacy', 'dsar-requests', { page, size, sort, state }],
    queryFn: ({ signal }) => listDsarRequests({ page, size, sort, state, signal }),
    placeholderData: (prev) => prev,
  })

  const envelope = parsePagedEnvelope(result.data)

  return {
    rows: envelope.data,
    page: envelope.page,
    isLoading: result.isLoading,
    isFetching: result.isFetching,
    isError: result.isError,
    error: result.error ?? null,
    refetch: result.refetch,
  }
}

/**
 * @param {string | null} id - DSAR request ID; query is disabled when null
 */
export function useDsarRequest(id) {
  const result = useQuery({
    queryKey: ['privacy', 'dsar-request', id],
    queryFn: ({ signal }) => getDsarRequest(/** @type {string} */ (id), { signal }),
    enabled: id != null,
  })
  return {
    dsar: result.data ?? null,
    isLoading: result.isLoading,
    isError: result.isError,
    error: result.error ?? null,
    refetch: result.refetch,
  }
}

/** Mutation for transitioning a DSAR request. */
export function useDsarTransition() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, event, note }) => transitionDsarRequest(id, { event, note }),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: ['privacy', 'dsar-request', id] })
      qc.invalidateQueries({ queryKey: ['privacy', 'dsar-requests'] })
    },
  })
}

/**
 * Mutation for requesting a fresh short-lived export download URL.
 * Never cached — always fetched fresh, never persisted in state or storage.
 */
export function useDsarExportUrl() {
  return useMutation({
    mutationFn: ({ id }) => getDsarExportUrl(id),
    retry: false,
  })
}

/**
 * Derives remaining days from a server-supplied dueAt ISO timestamp.
 * Returns a negative integer when overdue.
 *
 * @param {string} dueAt  ISO 8601 date string from the server
 * @returns {number}
 */
export function computeRemainingDays(dueAt) {
  const due = new Date(dueAt)
  const now = new Date()
  const msPerDay = 1000 * 60 * 60 * 24
  return Math.ceil((due.getTime() - now.getTime()) / msPerDay)
}
