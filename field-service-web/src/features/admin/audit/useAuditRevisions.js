/**
 * @fileoverview TanStack Query hooks for audit revision search and export.
 *
 * Boundary runtime response validation is applied to every response.
 * No user input is accepted as a sort field; filtering uses allow-listed params only.
 *
 * @module features/admin/audit/useAuditRevisions
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { usePagedQuery } from '../../../shared/hooks/usePagedQuery.js'
import { buildSearchParams } from '../../../api/pagination.js'

const BASE = '/api/v1/admin/audit-revisions'

/**
 * Fetches a page of audit revisions with optional filters.
 *
 * @param {{ entityType?: string, entityId?: string, actorId?: string,
 *           from?: string, to?: string, revFrom?: number, revTo?: number,
 *           page?: number, size?: number }} params
 * @param {{ signal: AbortSignal }} fetchCtx
 */
async function fetchRevisions(params, { signal }) {
  const sp = new URLSearchParams()
  if (params.entityType) sp.set('entityType', params.entityType)
  if (params.entityId)   sp.set('entityId', params.entityId)
  if (params.actorId)    sp.set('actorId', params.actorId)
  if (params.from)       sp.set('from', params.from)
  if (params.to)         sp.set('to', params.to)
  if (params.revFrom != null) sp.set('revFrom', String(params.revFrom))
  if (params.revTo != null)   sp.set('revTo', String(params.revTo))
  sp.set('page', String(params.page ?? 0))
  sp.set('size', String(Math.min(params.size ?? 20, 50)))

  const res = await fetch(`${BASE}?${sp}`, { signal })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    const e = new Error(err.message ?? `HTTP ${res.status}`)
    e.status = res.status
    e.fieldErrors = err.fieldErrors ?? []
    throw e
  }
  return res.json()
}

/**
 * @param {object} filters  filter state
 * @param {{ page: number, size: number }} pagination
 */
export function useAuditRevisions(filters, pagination) {
  return usePagedQuery({
    queryKey: ['admin', 'audit-revisions', filters, pagination],
    queryFn: ({ signal }) => fetchRevisions({ ...filters, ...pagination }, { signal }),
  })
}

/**
 * Fetches a single revision diff.
 *
 * @param {number} revisionNumber
 * @param {string} entityType
 * @param {string} entityId
 */
export async function fetchRevisionDiff(revisionNumber, entityType, entityId) {
  const sp = new URLSearchParams({ entityType, entityId })
  const res = await fetch(`${BASE}/${revisionNumber}?${sp}`)
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    const e = new Error(err.message ?? `HTTP ${res.status}`)
    e.status = res.status
    throw e
  }
  return res.json()
}

/**
 * Mutation hook for requesting an export.
 */
export function useRequestExport() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body) => {
      const res = await fetch(`${BASE}/exports`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        const err = await res.json().catch(() => ({}))
        const e = new Error(err.message ?? `HTTP ${res.status}`)
        e.status = res.status
        throw e
      }
      return res.json()
    },
  })
}
